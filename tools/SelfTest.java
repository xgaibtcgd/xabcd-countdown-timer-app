package com.morningmission.app;

import android.graphics.RectF;

/**
 * Pure-logic checks that run under a plain JVM, with no emulator and no Android SDK.
 *
 * <p>Run by tools/check.sh against the android-all framework jar. Note what is and is
 * not possible there: android.graphics.RectF and Color are ordinary Java and work fine,
 * which is what makes the layout sweep below possible, but Path is native-backed and
 * throws UnsatisfiedLinkError. That is why icon geometry is authored as float[] command
 * arrays rather than Path constants -- so it can be checked here too.
 *
 * <p>The layout sweep is the important one. The defect this whole rewrite exists to fix
 * was a layout that only held together at 1080x1920, so every commit re-proves it holds
 * at every shape of screen.
 */
public final class SelfTest {

    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        layoutSweep();
        buddyTable();
        hitMapBasics();
        engineContract();
        timeFormatting();
        artGeometry();
        animation();

        System.out.println("==> SelfTest: " + checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ assertions

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            if (failures <= 25) System.err.println("FAIL: " + what);
            else if (failures == 26) System.err.println("... further failures suppressed");
        }
    }

    // ----------------------------------------------------------------------- sweep

    private static final int[][] SCREENS = {
        // widthPx, heightPx, densityDpi
        {1080, 1920, 480},   // 16:9
        {1080, 2160, 480},   // 18:9
        {1080, 2280, 480},   // 19:9
        {1080, 2400, 480},   // 20:9  -- the stated target
        {1080, 2640, 480},   // 22:9
        { 720, 1280, 320},   // small 16:9
        {1440, 3120, 560},   // QHD+
        {1200, 1920, 320},   // 10:16 tablet-ish, the tightest case
    };

    private static final int[][] INSETS = {
        {0, 0}, {72, 48}, {90, 130}, {130, 0},
    };

    private static void layoutSweep() {
        Layout L = new Layout();
        for (int[] screen : SCREENS) {
            for (int[] inset : INSETS) {
                // Zero is included: a child can delete every task in the editor, and a
                // routine of no tasks previously produced a negative band minimum.
                for (int tasks = 0; tasks <= Layout.MAX_TASK_ROWS; tasks++) {
                    L.measure(screen[0], screen[1], inset[0], inset[1], screen[2],
                              tasks, tasks);
                    for (float scroll : new float[]{0f, 250f, 99999f}) {
                        L.scrollTasks(scroll);
                        L.scrollGrownUps(scroll);
                        L.scrollEditor(scroll);
                        String at = screen[0] + "x" + screen[1] + " insets " + inset[0] + "/"
                                  + inset[1] + " tasks " + tasks + " scroll " + (int) scroll;
                        checkHome(L, at);
                        checkAdventure(L, at);
                        buddyPoke(L, at);
                        checkOtherScreens(L, at);
                    }
                }
            }
        }
    }

    private static void checkHome(Layout L, String at) {
        RectF[] stack = {L.homeHeader, L.wordmark, L.hero, L.timerCard,
                         L.routineHeader, L.taskBand, L.startBtn, L.navBar};
        String[] names = {"homeHeader", "wordmark", "hero", "timerCard",
                          "routineHeader", "taskBand", "startBtn", "navBar"};

        for (int i = 0; i < stack.length; i++) {
            valid(stack[i], names[i] + " @ " + at);
            inside(stack[i], L.play, names[i] + " @ " + at);
            if (i > 0) {
                check(stack[i].top >= stack[i - 1].bottom - 0.6f,
                      names[i] + " overlaps " + names[i - 1] + " @ " + at);
            }
        }

        // The failure the old clamp actually produced: Start Morning under the nav bar.
        check(L.startBtn.bottom <= L.navBar.top + 0.6f,
              "startBtn runs into navBar @ " + at);
        check(L.navBar.bottom <= L.play.bottom + 0.6f,
              "navBar past the safe area @ " + at);

        valid(L.gearChip, "gearChip @ " + at);
        valid(L.grownUpsChip, "grownUpsChip @ " + at);
        valid(L.buddySlot, "buddySlot @ " + at);
        valid(L.editChip, "editChip @ " + at);
        valid(L.timerPencil, "timerPencil @ " + at);

        check(L.gearChip.right < L.grownUpsChip.left,
              "gearChip and grownUpsChip overlap @ " + at);

        float minTouch = L.minTouchUnits();
        for (int i = 0; i < 7; i++) {
            valid(L.minuteBubble[i], "minuteBubble[" + i + "] @ " + at);
            inside(L.minuteBubble[i], L.hero, "minuteBubble[" + i + "] @ " + at);
        }
        for (int i = 0; i < 7; i++) {
            for (int j = i + 1; j < 7; j++) {
                check(!RectF.intersects(L.minuteBubble[i], L.minuteBubble[j]),
                      "minuteBubble " + i + " and " + j + " overlap @ " + at);
            }
        }
        check(!RectF.intersects(L.buddySlot, L.minuteBubble[0]),
              "buddy overlaps the minute bubbles @ " + at);

        for (int i = 0; i < L.taskRowCount; i++) {
            valid(L.taskRow[i], "taskRow[" + i + "] @ " + at);
            check(L.taskRow[i].height() >= minTouch - 0.6f,
                  "taskRow is under the 48dp touch target @ " + at
                  + " (" + L.taskRow[i].height() + " < " + minTouch + ")");
            if (i > 0) {
                check(L.taskRow[i].top >= L.taskRow[i - 1].bottom - 0.6f,
                      "taskRow " + i + " overlaps " + (i - 1) + " @ " + at);
            }
        }
        // Scrolling must not run past the content.
        if (L.taskContentHeight > L.taskBand.height() && L.taskRowCount > 0) {
            check(L.taskRow[L.taskRowCount - 1].bottom >= L.taskBand.bottom - 0.6f,
                  "task list over-scrolled past its last row @ " + at);
        }

        check(L.startBtn.height() >= minTouch - 0.6f, "startBtn under touch target @ " + at);
        for (int i = 0; i < Layout.NAV_ITEMS; i++) {
            valid(L.navItem[i], "navItem[" + i + "] @ " + at);
            check(L.navItem[i].width() >= minTouch - 0.6f,
                  "navItem[" + i + "] under touch target @ " + at);
        }
    }

    private static void checkAdventure(Layout L, String at) {
        RectF[] stack = {L.advClock, L.advTally, L.advScene, L.advTaskCard, L.advAction};
        String[] names = {"advClock", "advTally", "advScene", "advTaskCard", "advAction"};
        for (int i = 0; i < stack.length; i++) {
            valid(stack[i], names[i] + " @ " + at);
            inside(stack[i], L.play, names[i] + " @ " + at);
            if (i > 0) {
                check(stack[i].top >= stack[i - 1].bottom - 0.6f,
                      names[i] + " overlaps " + names[i - 1] + " @ " + at);
            }
        }
        valid(L.advBack, "advBack @ " + at);
        valid(L.advPause, "advPause @ " + at);
        check(L.advBack.right < L.advPause.left, "advBack and advPause overlap @ " + at);

        valid(L.advGoal, "advGoal @ " + at);
        valid(L.advTrail, "advTrail @ " + at);
        valid(L.advProgress, "advProgress @ " + at);
        inside(L.advGoal, L.advScene, "advGoal @ " + at);
        // The lane used to have to stop short of the goal entirely, which cost it the
        // right third of the screen. The goal now stands ON the lane at its far end, so
        // the buddy arrives at it: what must hold is that the goal is still beyond the
        // walk, and that the walk is long enough to lay collectibles along.
        check(L.advGoal.centerX() > L.advTrail.right,
              "the goal should stand beyond the end of the lane @ " + at);
        check(L.advTrail.right <= L.advScene.right + 0.6f
              && L.advTrail.left >= L.advScene.left - 0.6f,
              "the buddy's lane runs outside the scene @ " + at);
        check(L.advTrail.width() > Layout.W * 0.55f,
              "the buddy's lane is too short to read as a journey @ " + at);

        valid(L.advMute, "advMute @ " + at);
        inside(L.advMute, L.play, "advMute @ " + at);
        // The quick mute sits in the clock band, beside the card rather than over it,
        // and clear of the chip above it.
        check(L.advMute.left > L.advClock.right - 0.6f,
              "the mute chip overlaps the clock card @ " + at);
        check(L.advMute.top > L.advPause.bottom - 0.6f,
              "the mute chip overlaps the pause chip @ " + at);
        check(L.advMute.bottom < L.advTally.top + 0.6f,
              "the mute chip runs into the tally band @ " + at);

        float minTouch = L.minTouchUnits();
        check(L.advAction.height() >= minTouch - 0.6f, "advAction under touch target @ " + at);
        check(L.advPause.width() >= minTouch - 0.6f, "advPause under touch target @ " + at);
    }

    /**
     * Poking the buddy.
     *
     * <p>The buddy's x is a function of the clock, so no hit region can follow it: the
     * screen registers the whole walking strip and does the real test in onPressDown.
     * That leaves two ways for the poke to quietly stop working -- the test drifting off
     * the character, and the test accepting points the registered strip does not contain,
     * which never reach onPressDown at all -- and neither shows up as anything but a
     * buddy that ignores you somewhere along the trail.
     */
    private static void buddyPoke(Layout L, String at) {
        FakeClock clock = new FakeClock();
        Engine e = new Engine(clock);
        e.setRoutine(tasks(3), keys(3));
        e.start(10 * 60);

        RectF lane = new RectF();
        ScreenAdventure.laneBounds(L, lane);
        valid(lane, "the walking lane @ " + at);

        float feet = L.advTrail.centerY();
        for (int step = 0; step <= 10; step++) {
            if (step > 0) clock.advance(60_000L);
            float x = L.advTrail.left + L.advTrail.width() * e.progress();
            float body = feet - Math.min(L.advScene.height() * 0.46f, Layout.W * 0.42f) * 0.5f;

            check(ScreenAdventure.onBuddy(L, e, x, body),
                  "a tap on the buddy should poke it, step " + step + " @ " + at);
            check(!ScreenAdventure.onBuddy(L, e, x, L.play.top + 4f),
                  "a tap in the sky above the buddy should not poke it @ " + at);

            // The far end of the trail is not the buddy -- unless it has walked there,
            // which after ten of ten minutes it has.
            if (step < 8) {
                check(!ScreenAdventure.onBuddy(L, e, L.advTrail.right, body),
                      "a tap at the end of the trail should not poke a buddy still at "
                      + (int) (e.progress() * 100) + "% @ " + at);
            }

            // Everything the test accepts has to be inside the region that delivers it.
            for (float dx = -1f; dx <= 1f; dx += 0.5f) {
                for (float dy = -1f; dy <= 1f; dy += 0.5f) {
                    float height = Math.min(L.advScene.height() * 0.46f, Layout.W * 0.42f);
                    float px = x + dx * height * 0.45f;
                    float py = body + dy * height * 0.60f;
                    if (!ScreenAdventure.onBuddy(L, e, px, py)) continue;
                    check(px >= lane.left - 0.6f && px <= lane.right + 0.6f
                          && py >= lane.top - 0.6f && py <= lane.bottom + 0.6f,
                          "a tap that counts as on the buddy falls outside the lane"
                          + " region, so it never arrives @ " + at);
                }
            }
        }
    }

    private static void checkOtherScreens(Layout L, String at) {
        float minTouch = L.minTouchUnits();

        valid(L.cmpTitle, "cmpTitle @ " + at);
        valid(L.cmpStage, "cmpStage @ " + at);
        valid(L.cmpCard, "cmpCard @ " + at);
        valid(L.cmpPlayAgain, "cmpPlayAgain @ " + at);
        valid(L.cmpBackHome, "cmpBackHome @ " + at);
        check(L.cmpPlayAgain.bottom <= L.cmpBackHome.top + 0.6f,
              "Play Again overlaps Back to Home @ " + at);
        check(L.cmpBackHome.bottom <= L.play.bottom + 0.6f,
              "Back to Home past the safe area @ " + at);
        check(L.cmpPlayAgain.height() >= minTouch - 0.6f,
              "Play Again under touch target @ " + at);

        valid(L.pickSheet, "pickSheet @ " + at);
        valid(L.pickConfirm, "pickConfirm @ " + at);
        for (int i = 0; i < BuddyTheme.COUNT; i++) {
            valid(L.buddyCard[i], "buddyCard[" + i + "] @ " + at);
            inside(L.buddyCard[i], L.pickSheet, "buddyCard[" + i + "] @ " + at);
            check(L.buddyCard[i].width() >= minTouch - 0.6f
                  && L.buddyCard[i].height() >= minTouch - 0.6f,
                  "buddyCard[" + i + "] under touch target @ " + at);
            for (int j = i + 1; j < BuddyTheme.COUNT; j++) {
                check(!RectF.intersects(L.buddyCard[i], L.buddyCard[j]),
                      "buddyCard " + i + " and " + j + " overlap @ " + at);
            }
        }
        check(L.pickConfirm.bottom <= L.pickSheet.bottom + 0.6f,
              "picker confirm past the sheet @ " + at);

        valid(L.timeDisplay, "timeDisplay @ " + at);
        valid(L.timeMinus, "timeMinus @ " + at);
        valid(L.timePlus, "timePlus @ " + at);
        valid(L.timeSlider, "timeSlider @ " + at);
        valid(L.timeSet, "timeSet @ " + at);
        check(!RectF.intersects(L.timeMinus, L.timeDisplay),
              "the minus stepper overlaps the time display @ " + at);
        check(!RectF.intersects(L.timePlus, L.timeDisplay),
              "the plus stepper overlaps the time display @ " + at);
        check(L.timeMinus.width() >= minTouch - 0.6f, "minus stepper under touch target @ " + at);
        for (int i = 0; i < 7; i++) {
            valid(L.presetBubble[i], "presetBubble[" + i + "] @ " + at);
            for (int j = i + 1; j < 7; j++) {
                check(!RectF.intersects(L.presetBubble[i], L.presetBubble[j]),
                      "presetBubble " + i + " and " + j + " overlap @ " + at);
            }
        }

        for (int i = 0; i < Layout.GROWN_UP_ROWS; i++) {
            valid(L.guRow[i], "guRow[" + i + "] @ " + at);
            check(L.guRow[i].height() >= minTouch - 0.6f,
                  "guRow[" + i + "] under touch target @ " + at);
            if (i > 0) {
                check(L.guRow[i].top >= L.guRow[i - 1].bottom - 0.6f,
                      "guRow " + i + " overlaps " + (i - 1) + " @ " + at);
            }
        }
        valid(L.guPanel, "guPanel @ " + at);
        valid(L.guBand, "guBand @ " + at);
        check(L.guBand.bottom <= L.guPanel.top + 0.6f,
              "the grown-ups list runs into the unlock panel @ " + at);
        check(L.guPanel.bottom <= L.play.bottom + 0.6f,
              "the unlock panel is past the safe area @ " + at);

        valid(L.edSave, "edSave @ " + at);
        valid(L.edAdd, "edAdd @ " + at);
        check(L.edAdd.bottom <= L.edSave.top + 0.6f, "Add Task overlaps Save @ " + at);
        check(L.edSave.bottom <= L.play.bottom + 0.6f, "Save past the safe area @ " + at);
    }

    private static void valid(RectF r, String what) {
        check(r.left < r.right && r.top < r.bottom,
              what + " is degenerate " + fmt(r));
        check(!Float.isNaN(r.left) && !Float.isNaN(r.top)
              && !Float.isNaN(r.right) && !Float.isNaN(r.bottom),
              what + " has a NaN edge");
    }

    private static void inside(RectF r, RectF container, String what) {
        check(r.top >= container.top - 0.6f && r.bottom <= container.bottom + 0.6f,
              what + " escapes vertically: " + fmt(r) + " not within " + fmt(container));
        check(r.left >= container.left - 0.6f && r.right <= container.right + 0.6f,
              what + " escapes horizontally: " + fmt(r) + " not within " + fmt(container));
    }

    private static String fmt(RectF r) {
        return "[" + (int) r.left + "," + (int) r.top + " "
                   + (int) r.right + "," + (int) r.bottom + "]";
    }

    // ------------------------------------------------------------------ buddy table

    private static void buddyTable() {
        for (int i = -3; i <= BuddyTheme.COUNT + 3; i++) {
            check(BuddyTheme.of(i) != null, "BuddyTheme.of(" + i + ") returned null");
        }
        check(BuddyTheme.of(-1).index == 0, "a negative buddy index must fall back to the first");
        check(BuddyTheme.of(99).index == 0, "an out-of-range buddy index must fall back");

        for (int i = 0; i < BuddyTheme.COUNT; i++) {
            BuddyTheme b = BuddyTheme.ALL[i];
            check(b.index == i, "BuddyTheme " + i + " has the wrong index");
            check(notBlank(b.key) && notBlank(b.name) && notBlank(b.soundWord)
                  && notBlank(b.munchWord) && notBlank(b.collectOne) && notBlank(b.collectMany),
                  "BuddyTheme " + b.key + " has a blank string");
            check(b.artRes != 0, "BuddyTheme " + b.key + " has no art resource");
            check(b.soundRes != 0, "BuddyTheme " + b.key + " has no sound resource");
            check(b.eatRes != 0, "BuddyTheme " + b.key + " has no eating sound");
            // A poke and a bite must not make the same noise, which is the whole reason
            // the eating sounds exist as a second set rather than reusing the tap.
            check(b.eatRes != b.soundRes,
                  "BuddyTheme " + b.key + " eats with its own tap sound");
            check(b.victoryRes != 0, "BuddyTheme " + b.key + " has no victory fanfare");
            check(b.victoryRes != b.soundRes && b.victoryRes != b.eatRes,
                  "BuddyTheme " + b.key + " celebrates with one of its own blips");
            check(b.backdropRes != 0, "BuddyTheme " + b.key + " has no backdrop");
            check(opaque(b.primary) && opaque(b.accent) && opaque(b.light)
                  && opaque(b.ink) && opaque(b.dark) && opaque(b.body),
                  "BuddyTheme " + b.key + " has a non-opaque palette colour");
            // The ink colour is used for text on the light tint, so it has to be dark.
            check(luminance(b.ink) < 0.42f,
                  "BuddyTheme " + b.key + " ink is too light to read: " + luminance(b.ink));
            check(luminance(b.light) > 0.80f,
                  "BuddyTheme " + b.key + " light tint is too dark for a card fill");
            check(contrast(b.ink, b.light) >= 4.5f,
                  "BuddyTheme " + b.key + " ink on light fails 4.5:1 contrast: "
                  + contrast(b.ink, b.light));
        }

        // No two rows may share a resource: that is what a copy-pasted row looks like,
        // and it is invisible until you notice two characters sounding the same.
        for (int i = 0; i < BuddyTheme.COUNT; i++) {
            for (int j = i + 1; j < BuddyTheme.COUNT; j++) {
                BuddyTheme a = BuddyTheme.ALL[i], b = BuddyTheme.ALL[j];
                check(a.artRes != b.artRes && a.soundRes != b.soundRes
                      && a.eatRes != b.eatRes && a.victoryRes != b.victoryRes
                      && a.backdropRes != b.backdropRes,
                      a.key + " and " + b.key + " share a resource");
            }
        }

        // Palette separation. Deliberately a weak bar, because the palettes are sampled
        // from the character art and are not free to move: shark and cloud are both
        // around hue 203 and shark and pug are both orange, because those characters
        // genuinely are. They are told apart on screen by their artwork and by their
        // scenery (a deep reef against a pale sky), not by the accent alone. What this
        // catches is the defect that would actually happen -- two rows of the table
        // accidentally sharing a palette after a copy-paste.
        for (int i = 0; i < BuddyTheme.COUNT; i++) {
            for (int j = i + 1; j < BuddyTheme.COUNT; j++) {
                BuddyTheme a = BuddyTheme.ALL[i], b = BuddyTheme.ALL[j];
                check(distinguishable(a.primary, b.primary),
                      "primaries of " + a.key + " and " + b.key + " are effectively identical");
                check(distinguishable(a.light, b.light),
                      "light tints of " + a.key + " and " + b.key + " are effectively identical");
            }
        }

        // Each theme needs internal contrast, or an icon's accent element disappears
        // into its own hero shape.
        for (int i = 0; i < BuddyTheme.COUNT; i++) {
            BuddyTheme b = BuddyTheme.ALL[i];
            check(colorDistance(b.primary, b.accent) > 0.05f,
                  b.key + " accent is too close to its own primary to read as detail");
            check(colorDistance(b.primary, b.light) > 0.15f,
                  b.key + " light tint is too close to its own primary");
        }

        // Every buddy's treat is named after the thing its art actually draws. Only the
        // two irregular plurals were pinned before, which left the copy-paste that gave
        // Sweet Kitty the shark's words undefended: it drew a heart and counted "fish
        // treats" on screen for as long as that row existed.
        String[][] nouns = {
            {"mini burger", "mini burgers"},   // a mini burger
            {"honey drop", "honey drops"},     // a honey drop
            {"bone", "bones"},                 // a bone
            {"fish", "fish"},                  // a fish
            {"leaf", "leaves"},                // a leaf
            {"star", "stars"},                 // a star
            {"heart", "hearts"},               // a heart
            {"melon slice", "melon slices"},   // a melon slice
        };
        check(nouns.length == BuddyTheme.COUNT, "the treat-noun table is the wrong length");

        // Scene indexes seven parallel colour tables by the buddy's own index, so a new
        // character whose row nobody extended does not misdraw -- it throws
        // ArrayIndexOutOfBounds the first time its procedural scene is built.
        check(Scene.ENVIRONMENT_NAMES.length == BuddyTheme.COUNT
              && Scene.SKY_TOP.length == BuddyTheme.COUNT
              && Scene.SKY_MID.length == BuddyTheme.COUNT
              && Scene.SKY_LOW.length == BuddyTheme.COUNT
              && Scene.GROUND_NEAR.length == BuddyTheme.COUNT
              && Scene.GROUND_FAR.length == BuddyTheme.COUNT
              && Scene.HORIZON.length == BuddyTheme.COUNT
              && Scene.SCRIM.length == BuddyTheme.COUNT,
              "Scene needs one environment per buddy; some table is still "
              + Scene.SKY_TOP.length + " long against " + BuddyTheme.COUNT + " buddies");
        for (int i = 0; i < BuddyTheme.COUNT; i++) {
            check(notBlank(Scene.ENVIRONMENT_NAMES[i]),
                  "environment " + i + " has no name");
            check(opaque(Scene.SKY_TOP[i]) && opaque(Scene.SKY_MID[i])
                  && opaque(Scene.SKY_LOW[i]) && opaque(Scene.GROUND_NEAR[i])
                  && opaque(Scene.GROUND_FAR[i]),
                  "environment " + Scene.ENVIRONMENT_NAMES[i] + " has a see-through band");
            check(Scene.HORIZON[i] > 0.3f && Scene.HORIZON[i] < 0.85f,
                  "environment " + Scene.ENVIRONMENT_NAMES[i] + " puts the horizon at "
                  + Scene.HORIZON[i] + ", which leaves no sky or no ground");
        }
        for (int i = 0; i < BuddyTheme.COUNT; i++) {
            BuddyTheme b = BuddyTheme.of(i);
            check(b.collectibleNoun(1).equals(nouns[i][0]),
                  b.key + " singular treat noun is \"" + b.collectibleNoun(1)
                  + "\", expected \"" + nouns[i][0] + "\"");
            check(b.collectibleNoun(4).equals(nouns[i][1]),
                  b.key + " plural treat noun is \"" + b.collectibleNoun(4)
                  + "\", expected \"" + nouns[i][1] + "\"");
        }

        // And no two buddies share a treat noun, which is what a copy-paste looks like.
        for (int a = 0; a < BuddyTheme.COUNT; a++) {
            for (int b = a + 1; b < BuddyTheme.COUNT; b++) {
                check(!BuddyTheme.of(a).collectibleNoun(4)
                          .equals(BuddyTheme.of(b).collectibleNoun(4)),
                      BuddyTheme.of(a).key + " and " + BuddyTheme.of(b).key
                      + " count the same treat");
            }
        }
    }

    private static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }

    private static boolean opaque(int color) { return (color >>> 24) == 0xFF; }

    private static float channel(int c) {
        float s = ((c & 0xFF) / 255f);
        return s <= 0.03928f ? s / 12.92f : (float) Math.pow((s + 0.055) / 1.055, 2.4);
    }

    private static float luminance(int color) {
        return 0.2126f * channel(color >> 16) + 0.7152f * channel(color >> 8) + 0.0722f * channel(color);
    }

    private static float contrast(int a, int b) {
        float la = luminance(a), lb = luminance(b);
        float hi = Math.max(la, lb), lo = Math.min(la, lb);
        return (hi + 0.05f) / (lo + 0.05f);
    }

    /** Rough perceptual distance, 0..1-ish. */
    private static float colorDistance(int a, int b) {
        float dr = ((a >> 16 & 0xFF) - (b >> 16 & 0xFF)) / 255f;
        float dg = ((a >> 8 & 0xFF) - (b >> 8 & 0xFF)) / 255f;
        float db = ((a & 0xFF) - (b & 0xFF)) / 255f;
        return (float) Math.sqrt(0.30 * dr * dr + 0.59 * dg * dg + 0.11 * db * db);
    }

    /** Hue in degrees, saturation and value, each 0..1 except hue. */
    private static float[] hsv(int color) {
        float r = (color >> 16 & 0xFF) / 255f, g = (color >> 8 & 0xFF) / 255f, b = (color & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float d = max - min;
        float h;
        if (d < 1e-6f) h = 0f;
        else if (max == r) h = 60f * (((g - b) / d) % 6f);
        else if (max == g) h = 60f * ((b - r) / d + 2f);
        else h = 60f * ((r - g) / d + 4f);
        if (h < 0f) h += 360f;
        return new float[]{h, max < 1e-6f ? 0f : d / max, max};
    }

    private static float hueGap(float a, float b) {
        float d = Math.abs(a - b) % 360f;
        return d > 180f ? 360f - d : d;
    }

    /** Two colours a person could tell apart side by side, by hue or by saturation/value. */
    private static boolean distinguishable(int a, int b) {
        float[] x = hsv(a), y = hsv(b);
        return hueGap(x[0], y[0]) >= 6f
            || Math.abs(x[1] - y[1]) >= 0.10f
            || Math.abs(x[2] - y[2]) >= 0.10f;
    }

    // ------------------------------------------------------------------ animation

    /**
     * Every state moves, stays within sane bounds, and -- the point of the rewrite --
     * differs from every other state. In the old build five task states shared one
     * identical branch and seven more had no branch at all.
     */
    private static void animation() {
        Anim.Transform tr = new Anim.Transform();

        for (int state = 0; state < Anim.STATE_COUNT; state++) {
            boolean moved = false;
            for (float t = 0f; t < 6f; t += 0.05f) {
                Anim.solve(state, t, tr);
                check(finite(tr), "state " + state + " produced a non-finite transform at t=" + t);
                check(Math.abs(tr.dx) <= 60f && Math.abs(tr.dy) <= 60f,
                      "state " + state + " translates too far at t=" + t
                      + ": " + tr.dx + "," + tr.dy);
                check(Math.abs(tr.rotation) <= 361f,
                      "state " + state + " rotates too far at t=" + t + ": " + tr.rotation);
                check(tr.scaleX > 0.6f && tr.scaleX < 1.5f
                      && tr.scaleY > 0.6f && tr.scaleY < 1.5f,
                      "state " + state + " scales out of range at t=" + t);
                if (Math.abs(tr.dx) > 1f || Math.abs(tr.dy) > 1f
                    || Math.abs(tr.rotation) > 1f
                    || Math.abs(tr.scaleY - 1f) > 0.01f) moved = true;
            }
            check(moved, "state " + state + " never actually moves");
        }

        drift();
        feastMoves();
        signatureMoves();
        temperaments();

        // No two states may be interchangeable. Sample a signature over several seconds
        // and require a meaningful difference.
        for (int a = 0; a < Anim.STATE_COUNT; a++) {
            for (int b = a + 1; b < Anim.STATE_COUNT; b++) {
                check(motionDistance(a, b) > 1.5f,
                      "states " + a + " and " + b + " move identically; every task is"
                      + " supposed to have its own motion");
            }
        }

        // Task keys must each reach their own motion.
        for (int i = 0; i < Art.ACT_COUNT; i++) {
            check(Art.activityKind(Art.ACTIVITY_KEYS[i]) < Anim.STATE_COUNT,
                  "task " + Art.ACTIVITY_KEYS[i] + " maps outside the motion table");
        }

        // Blending must settle, and must not overshoot on a long frame.
        Anim.Blend blend = new Anim.Blend();
        blend.snap(Anim.IDLE);
        blend.set(Anim.DANCE);
        for (int i = 0; i < 40; i++) {
            blend.update(1f / 60f);
            blend.solve(i / 60f, 1f / 60f, tr);
            check(finite(tr), "blending produced a non-finite transform");
        }
        check(blend.state() == Anim.DANCE, "a blend should end on its target state");

        blend.snap(Anim.IDLE);
        blend.set(Anim.HURRY);
        blend.update(5f);                       // one absurdly long frame
        blend.solve(5f, 5f, tr);
        check(finite(tr), "a long frame must not produce a non-finite transform");
        check(tr.scaleY > 0.6f && tr.scaleY < 1.5f,
              "squash and stretch must stay bounded after a long frame: " + tr.scaleY);
    }

    /**
     * The collectible actions.
     *
     * <p>These are reached through a switch on a FEAST_* constant with a {@code default}
     * that falls through to the plain bite, so a new constant added to the list without
     * a case of its own compiles, runs, and quietly gives that character somebody else's
     * move. Nothing but a distinctness check finds that. The rest is the same bar the
     * idle states clear: bounded, finite, and at rest outside the beat.
     */
    private static void feastMoves() {
        Anim.Transform tr = new Anim.Transform();

        for (int kind = 0; kind < Anim.FEAST_COUNT; kind++) {
            Anim.feast(kind, -0.1f, tr);
            check(atRest(tr), "feast " + kind + " moves before its beat starts");
            Anim.feast(kind, 1f, tr);
            check(atRest(tr), "feast " + kind + " is still moving after its beat ends");

            boolean moved = false;
            for (float p = 0f; p <= 1f; p += 0.005f) {
                Anim.feast(kind, p, tr);
                check(finite(tr), "feast " + kind + " went non-finite at p=" + p);
                check(Math.abs(tr.dx) <= 130f && Math.abs(tr.dy) <= 130f,
                      "feast " + kind + " translates too far at p=" + p
                      + ": " + tr.dx + "," + tr.dy);
                check(Math.abs(tr.rotation) <= 361f,
                      "feast " + kind + " rotates too far at p=" + p + ": " + tr.rotation);
                check(tr.scaleX > 0.5f && tr.scaleX < 1.6f
                      && tr.scaleY > 0.5f && tr.scaleY < 1.6f,
                      "feast " + kind + " scales out of range at p=" + p
                      + ": " + tr.scaleX + "," + tr.scaleY);
                if (!atRest(tr)) moved = true;

                // Scaling the move down has to scale it down, not change its shape.
                Anim.feast(kind, p, 0f, tr);
                check(atRest(tr), "feast " + kind + " still moves at zero strength, p=" + p);
            }
            check(moved, "feast " + kind + " never actually moves");
        }

        for (int a = 0; a < Anim.FEAST_COUNT; a++) {
            for (int b = a + 1; b < Anim.FEAST_COUNT; b++) {
                check(feastDistance(a, b) > 1.5f,
                      "feast moves " + a + " and " + b + " are the same movement; one of"
                      + " them is falling through to the default bite");
            }
        }

        // And every character reaches one of them, with none shared: eight buddies that
        // all lunge are one buddy drawn eight ways.
        for (int i = 0; i < BuddyTheme.COUNT; i++) {
            BuddyTheme b = BuddyTheme.ALL[i];
            check(b.feastKind >= 0 && b.feastKind < Anim.FEAST_COUNT,
                  b.key + " has a feast kind outside the table: " + b.feastKind);
            for (int j = i + 1; j < BuddyTheme.COUNT; j++) {
                check(b.feastKind != BuddyTheme.ALL[j].feastKind,
                      b.key + " and " + BuddyTheme.ALL[j].key + " share a collectible"
                      + " action");
            }
        }
    }

    /**
     * The signature moves -- a character's party piece, run when it is poked.
     *
     * <p>Same trap as the feast table: the switch behind it has a default, so a
     * constant with no case of its own quietly gives that character the plain hop. And
     * one more on top -- a signature that comes out the same as a FEAST_* move means
     * poking the buddy and feeding it look identical, which is the entire point of
     * having both.
     */
    private static void signatureMoves() {
        Anim.Transform tr = new Anim.Transform();

        for (int kind = 0; kind < Anim.SIG_COUNT; kind++) {
            Anim.signature(kind, -0.1f, tr);
            check(atRest(tr), "signature " + kind + " moves before it starts");
            Anim.signature(kind, 1f, tr);
            check(atRest(tr), "signature " + kind + " is still moving after it ends");

            boolean moved = false;
            for (float p = 0f; p <= 1f; p += 0.005f) {
                Anim.signature(kind, p, tr);
                check(finite(tr), "signature " + kind + " went non-finite at p=" + p);
                check(Math.abs(tr.dx) <= 130f && Math.abs(tr.dy) <= 130f,
                      "signature " + kind + " travels too far at p=" + p
                      + ": " + tr.dx + "," + tr.dy);
                check(Math.abs(tr.rotation) <= 361f,
                      "signature " + kind + " rotates too far at p=" + p);
                check(tr.scaleX > 0.5f && tr.scaleX < 1.6f
                      && tr.scaleY > 0.5f && tr.scaleY < 1.6f,
                      "signature " + kind + " scales out of range at p=" + p
                      + ": " + tr.scaleX + "," + tr.scaleY);
                if (!atRest(tr)) moved = true;

                Anim.signature(kind, p, 0f, tr);
                check(atRest(tr), "signature " + kind + " still moves at zero strength");
            }
            check(moved, "signature " + kind + " never actually moves");
        }

        for (int a = 0; a < Anim.SIG_COUNT; a++) {
            for (int b = a + 1; b < Anim.SIG_COUNT; b++) {
                check(moveDistance(Anim.signatureMoveId(a), Anim.signatureMoveId(b)) > 1.5f,
                      "signatures " + a + " and " + b + " are the same movement; one is"
                      + " falling through to the default hop");
            }
            for (int f = 0; f < Anim.FEAST_COUNT; f++) {
                check(moveDistance(Anim.signatureMoveId(a), Anim.feastMoveId(f)) > 1.5f,
                      "signature " + a + " and feast move " + f + " are the same"
                      + " movement; poking and feeding would look identical");
            }
        }

        // And the same rule the feast moves are held to: one each, none shared.
        for (int i = 0; i < BuddyTheme.COUNT; i++) {
            BuddyTheme b = BuddyTheme.ALL[i];
            check(b.signatureKind >= 0 && b.signatureKind < Anim.SIG_COUNT,
                  b.key + " has a signature outside the table: " + b.signatureKind);
            for (int j = i + 1; j < BuddyTheme.COUNT; j++) {
                check(b.signatureKind != BuddyTheme.ALL[j].signatureKind,
                      b.key + " and " + BuddyTheme.ALL[j].key + " share a signature move");
            }
        }
    }

    /**
     * Temperament: the dials that make each character move like itself.
     *
     * <p>The failure this is really guarding is silent. A temperament that is declared
     * but never reaches {@code solve} -- one of the two call sites missed, say -- leaves
     * every character moving exactly as before, and nothing about that looks broken. So
     * the check is not that the numbers exist but that they change the motion: sampled
     * against PLAIN, and against each other.
     */
    private static void temperaments() {
        Anim.Transform tr = new Anim.Transform();

        for (int i = 0; i < BuddyTheme.COUNT; i++) {
            BuddyTheme b = BuddyTheme.ALL[i];
            Anim.Temperament how = b.temperament;
            check(how != null, b.key + " has no temperament");
            check(how.tempo > 0.4f && how.tempo < 2.5f,
                  b.key + " tempo " + how.tempo + " is outside anything watchable");
            check(how.bounce >= 0f && how.bounce < 3f
                  && how.sway >= 0f && how.sway < 3f
                  && how.tilt >= 0f && how.tilt < 3f
                  && how.squash >= 0f && how.squash < 3f
                  && how.hover >= 0f && how.hover <= 1.5f,
                  b.key + " has a temperament dial outside its range");

            // Bounded through every state, with the dials on.
            for (int state = 0; state < Anim.STATE_COUNT; state++) {
                for (float t = 0f; t < 6f; t += 0.05f) {
                    Anim.solve(state, how, t, tr);
                    check(finite(tr), b.key + " state " + state + " went non-finite");
                    check(Math.abs(tr.dx) <= 90f && Math.abs(tr.dy) <= 90f,
                          b.key + " state " + state + " travels too far at t=" + t
                          + ": " + tr.dx + "," + tr.dy);
                    check(tr.scaleX > 0.6f && tr.scaleX < 1.5f
                          && tr.scaleY > 0.6f && tr.scaleY < 1.5f,
                          b.key + " state " + state + " scales out of range at t=" + t);
                }
            }

            // Anything with hover is off the ground, in every state, always. A hover
            // value too small to clear the state's own bob is a bee that keeps
            // touching down, which is worse than not hovering at all.
            if (how.hover > 0f) {
                for (int state = 0; state < Anim.STATE_COUNT; state++) {
                    for (float t = 0f; t < 6f; t += 0.02f) {
                        Anim.solve(state, how, t, tr);
                        check(tr.dy < 0f,
                              b.key + " hovers, but state " + state + " puts it on the"
                              + " ground at t=" + t + " (dy=" + tr.dy + ")");
                    }
                }
            }
        }

        // Every one of the eight was given a body of its own. PLAIN stays in the code
        // as the documented default for a caller that has no character to hand -- it is
        // not something a row in the table should settle for.
        for (int i = 0; i < BuddyTheme.COUNT; i++) {
            BuddyTheme b = BuddyTheme.ALL[i];
            check(temperamentDistance(b.temperament, Anim.PLAIN) > 1.5f,
                  b.key + " still moves like the shared default");
        }

        // And no two characters may be interchangeable. This is the check that would
        // survive the dials being declared and never passed to solve: ignored, all
        // eight would come out identical and every pair here would fail.
        for (int i = 0; i < BuddyTheme.COUNT; i++) {
            for (int j = i + 1; j < BuddyTheme.COUNT; j++) {
                check(temperamentDistance(BuddyTheme.ALL[i].temperament,
                                          BuddyTheme.ALL[j].temperament) > 1.5f,
                      BuddyTheme.ALL[i].key + " and " + BuddyTheme.ALL[j].key
                      + " move identically");
            }
        }
    }

    /** Mean absolute difference between two temperaments, over every state. */
    private static float temperamentDistance(Anim.Temperament a, Anim.Temperament b) {
        Anim.Transform ta = new Anim.Transform();
        Anim.Transform tb = new Anim.Transform();
        float total = 0f;
        int samples = 0;
        for (int state = 0; state < Anim.STATE_COUNT; state++) {
            for (float t = 0f; t < 4f; t += 0.05f) {
                Anim.solve(state, a, t, ta);
                Anim.solve(state, b, t, tb);
                total += Math.abs(ta.dx - tb.dx) + Math.abs(ta.dy - tb.dy)
                       + Math.abs(ta.rotation - tb.rotation) * 0.8f
                       + Math.abs(ta.scaleY - tb.scaleY) * 120f;
                samples++;
            }
        }
        return samples == 0 ? 0f : total / samples;
    }

    /** Mean absolute difference between two one-shot moves, feast or signature. */
    private static float moveDistance(int a, int b) {
        Anim.Transform ta = new Anim.Transform();
        Anim.Transform tb = new Anim.Transform();
        float total = 0f;
        int samples = 0;
        for (float p = 0f; p <= 1f; p += 0.004f) {
            Anim.move(a, p, 1f, ta);
            Anim.move(b, p, 1f, tb);
            total += Math.abs(ta.dx - tb.dx) + Math.abs(ta.dy - tb.dy)
                   + Math.abs(ta.rotation - tb.rotation) * 0.8f
                   + Math.abs(ta.scaleY - tb.scaleY) * 120f;
            samples++;
        }
        return samples == 0 ? 0f : total / samples;
    }

    private static boolean atRest(Anim.Transform tr) {
        return Math.abs(tr.dx) < 0.001f && Math.abs(tr.dy) < 0.001f
            && Math.abs(tr.rotation) < 0.001f
            && Math.abs(tr.scaleX - 1f) < 0.001f && Math.abs(tr.scaleY - 1f) < 0.001f;
    }

    /** Mean absolute difference between two collectible actions across the beat. */
    private static float feastDistance(int a, int b) {
        Anim.Transform ta = new Anim.Transform();
        Anim.Transform tb = new Anim.Transform();
        float total = 0f;
        int samples = 0;
        for (float p = 0f; p <= 1f; p += 0.004f) {
            Anim.feast(a, p, ta);
            Anim.feast(b, p, tb);
            total += Math.abs(ta.dx - tb.dx) + Math.abs(ta.dy - tb.dy)
                   + Math.abs(ta.rotation - tb.rotation) * 0.8f
                   + Math.abs(ta.scaleY - tb.scaleY) * 120f;
            samples++;
        }
        return samples == 0 ? 0f : total / samples;
    }

    /**
     * The idle float on the minute bubbles and the time presets. The bubbles sit a few
     * units apart, so "within limits" is a real constraint and not a figure of speech:
     * the caller passes the gap it has and the drift must never exceed it.
     */
    private static void drift() {
        Anim.Transform tr = new Anim.Transform();
        for (int seed = 0; seed < 12; seed++) {
            for (float limit : new float[]{0f, 2f, 6.5f, 40f}) {
                boolean moved = false;
                float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
                for (float t = 0f; t < 45f; t += 0.05f) {
                    Anim.drift(seed, t, limit, tr);
                    check(finite(tr), "drift " + seed + " went non-finite at t=" + t);
                    check(Math.abs(tr.dx) <= limit + 0.001f
                          && Math.abs(tr.dy) <= limit + 0.001f,
                          "drift " + seed + " left its limit " + limit + " at t=" + t
                          + ": " + tr.dx + "," + tr.dy);
                    check(tr.scaleX >= 0.97f && tr.scaleX <= 1.03f
                          && tr.scaleY >= 0.97f && tr.scaleY <= 1.03f,
                          "drift " + seed + " scaled out of range at t=" + t);
                    if (Math.abs(tr.dx) > limit * 0.2f) moved = true;
                    minX = Math.min(minX, tr.dx);
                    maxX = Math.max(maxX, tr.dx);
                }
                check(limit == 0f || moved, "drift " + seed + " never moves at limit " + limit);
                check(limit == 0f || maxX - minX > limit * 0.5f,
                      "drift " + seed + " barely uses its limit " + limit);
            }
        }

        // Two controls must not float in lockstep, or a row of them reads as one object.
        Anim.Transform a = new Anim.Transform();
        Anim.Transform b = new Anim.Transform();
        for (int seed = 0; seed < 11; seed++) {
            float apart = 0f;
            for (float t = 0f; t < 20f; t += 0.1f) {
                Anim.drift(seed, t, 10f, a);
                Anim.drift(seed + 1, t, 10f, b);
                apart += Math.abs(a.dx - b.dx) + Math.abs(a.dy - b.dy);
            }
            check(apart > 100f, "drift seeds " + seed + " and " + (seed + 1)
                  + " move together; the bubbles are supposed to be out of phase");
        }
    }

    private static boolean finite(Anim.Transform tr) {
        return !Float.isNaN(tr.dx) && !Float.isNaN(tr.dy) && !Float.isNaN(tr.rotation)
            && !Float.isNaN(tr.scaleX) && !Float.isNaN(tr.scaleY)
            && !Float.isInfinite(tr.dx) && !Float.isInfinite(tr.dy);
    }

    /** Mean absolute difference between two states sampled over time. */
    private static float motionDistance(int a, int b) {
        Anim.Transform ta = new Anim.Transform();
        Anim.Transform tb = new Anim.Transform();
        float total = 0f;
        int samples = 0;
        for (float t = 0f; t < 8f; t += 0.04f) {
            Anim.solve(a, t, ta);
            Anim.solve(b, t, tb);
            total += Math.abs(ta.dx - tb.dx) + Math.abs(ta.dy - tb.dy)
                   + Math.abs(ta.rotation - tb.rotation) * 0.8f
                   + Math.abs(ta.scaleY - tb.scaleY) * 120f;
            samples++;
        }
        return samples == 0 ? 0f : total / samples;
    }

    // ------------------------------------------------------------------- geometry

    /**
     * Every shape is well-formed and stays inside its 100x100 box.
     *
     * <p>This is the check that motivated authoring geometry as float[] rather than as
     * Path constants: Path is native and unavailable here, so a shape that ran outside
     * its box would otherwise only show up as clipping on a device.
     */
    private static void artGeometry() {
        for (int kind = 0; kind < Art.ACT_COUNT; kind++) {
            String name = Art.ACTIVITY_KEYS[kind];
            float[][] shapes = Art.ACTIVITY_SHAPES[kind];
            int[] colors = Art.ACTIVITY_COLORS[kind];
            int[] flags = Art.ACTIVITY_FLAGS[kind];
            check(shapes != null && shapes.length > 0, "activity icon " + name + " has no shapes");
            check(colors != null && flags != null && shapes.length == colors.length
                  && shapes.length == flags.length,
                  "activity icon " + name + " has mismatched shape, colour and flag arrays");
            check(shapes.length <= 4,
                  "activity icon " + name + " has " + shapes.length + " parts; the ceiling is"
                  + " four, or it stops reading at task-row size");
            for (int i = 0; i < shapes.length; i++) {
                checkShape(shapes[i], "activity " + name + " part " + i);
            }
        }

        check(Art.ACTIVITY_KEYS.length == Art.ACT_COUNT, "activity key list is the wrong length");
        check(Art.ACTIVITY_NAMES.length == Art.ACT_COUNT, "activity name list is the wrong length");
        check(Art.ACTIVITY_SUBTITLES.length == Art.ACT_COUNT, "activity subtitle list is the wrong length");
        for (int kind = 0; kind < Art.ACT_COUNT; kind++) {
            check(Art.activityKind(Art.ACTIVITY_KEYS[kind]) == kind,
                  "activity key " + Art.ACTIVITY_KEYS[kind] + " does not resolve to its own icon");
            check(notBlank(Art.ACTIVITY_NAMES[kind]) && notBlank(Art.ACTIVITY_SUBTITLES[kind]),
                  "activity " + Art.ACTIVITY_KEYS[kind] + " is missing a name or subtitle");
        }
        check(Art.activityKind("NOT_A_REAL_KEY") == Art.ACT_DRESS,
              "an unknown task key should fall back to Get Dressed");
        check(Art.activityKind(null) == Art.ACT_DRESS, "a null task key must not throw");

        for (int buddy = 0; buddy < BuddyTheme.COUNT; buddy++) {
            String name = BuddyTheme.ALL[buddy].key;

            float[][] coll = Art.COLLECTIBLE_SHAPES[buddy];
            check(coll != null && coll.length > 0, name + " has no collectible");
            check(coll.length == Art.COLLECTIBLE_COLORS[buddy].length
                  && coll.length == Art.COLLECTIBLE_FLAGS[buddy].length,
                  name + " collectible has mismatched arrays");
            check(coll.length <= 4,
                  name + " collectible has too many parts to read at 24 units across");
            for (int i = 0; i < coll.length; i++) {
                checkShape(coll[i], name + " collectible part " + i);
            }

            float[][] goal = Art.GOAL_SHAPES[buddy];
            check(goal != null && goal.length > 0, name + " has no goal");
            check(goal.length == Art.GOAL_COLORS[buddy].length
                  && goal.length == Art.GOAL_FLAGS[buddy].length,
                  name + " goal has mismatched arrays");
            for (int i = 0; i < goal.length; i++) {
                checkShape(goal[i], name + " goal part " + i);
            }
            int lid = Art.GOAL_LID[buddy];
            check(lid == -1 || (lid >= 0 && lid < goal.length),
                  name + " goal names a lid part that does not exist: " + lid);
        }
        check(Art.GOAL_NAMES.length == BuddyTheme.COUNT, "goal name list is the wrong length");

        bites();

        // One activity cue per routine task, in Art.ACT_* order. A table a row short
        // is silent on the last task and wrong on nothing, which is the kind of defect
        // that ships.
        check(Sounds.ACTIVITY.length == Art.ACT_COUNT,
              "the activity sound table is " + Sounds.ACTIVITY.length + " long against "
              + Art.ACT_COUNT + " tasks");
        check(Sounds.UI.length == Sounds.UI_COUNT
              && Sounds.CUE.length == Sounds.CUE_COUNT
              && Sounds.POKE.length == Sounds.POKE_COUNT,
              "a Sounds table does not match its own COUNT");
        int[][] tables = {Sounds.ACTIVITY, Sounds.UI, Sounds.CUE, Sounds.POKE};
        String[] tableNames = {"ACTIVITY", "UI", "CUE", "POKE"};
        for (int t = 0; t < tables.length; t++) {
            for (int i = 0; i < tables[t].length; i++) {
                check(tables[t][i] != 0,
                      "Sounds." + tableNames[t] + "[" + i + "] has no resource");
                for (int j = i + 1; j < tables[t].length; j++) {
                    check(tables[t][i] != tables[t][j],
                          "Sounds." + tableNames[t] + " uses one file for " + i
                          + " and " + j);
                }
            }
        }

        check(Art.GLYPH_NAMES.length == Art.GLYPH_COUNT,
              "the glyph name list is " + Art.GLYPH_NAMES.length + " long against "
              + Art.GLYPH_COUNT + " glyphs; the preview looks these up by name");
        for (int g = 0; g < Art.GLYPH_COUNT; g++) {
            check(Art.GLYPHS[g] != null && Art.GLYPHS[g].length > 0,
                  "UI glyph " + g + " is missing");
            checkShape(Art.GLYPHS[g], "glyph " + g);
            check(notBlank(Art.GLYPH_NAMES[g]), "glyph " + g + " has no name");
            for (int h = g + 1; h < Art.GLYPH_COUNT; h++) {
                check(!Art.GLYPH_NAMES[g].equals(Art.GLYPH_NAMES[h]),
                      "glyphs " + g + " and " + h + " share the name "
                      + Art.GLYPH_NAMES[g]);
            }
        }
    }

    /** Validates the command stream and the bounding box of one shape. */
    /**
     * The part-eaten collectibles.
     *
     * <p>The whole mechanism rests on one property: a bite circle has to STRADDLE the
     * item's outline. A circle that sits wholly inside punches a donut hole in the middle
     * of the food, and a circle that misses entirely does nothing at all -- both compile
     * and draw perfectly happily, so nothing but this check would catch them.
     */
    private static void bites() {
        float[] bounds = new float[4];
        float[] circle = new float[3];

        for (int buddy = 0; buddy < BuddyTheme.COUNT; buddy++) {
            String name = BuddyTheme.ALL[buddy].key;
            float[][] whole = Art.COLLECTIBLE_SHAPES[buddy];
            Art.shapeBounds(whole, bounds);
            check(bounds[2] > bounds[0] && bounds[3] > bounds[1],
                  name + " collectible has empty bounds");
            check(bounds[0] >= -1f && bounds[1] >= -1f
                  && bounds[2] <= 101f && bounds[3] <= 101f,
                  name + " collectible bounds leave the 100-unit box: "
                  + bounds[0] + "," + bounds[1] + " to " + bounds[2] + "," + bounds[3]);

            for (int b = 0; b < Art.BITE_COUNT - 1; b++) {
                Art.biteCircle(bounds, b, circle);
                float cx = circle[0], cy = circle[1], r = circle[2];
                check(r > 0f, name + " bite " + b + " has no radius");
                // A bite straddles the outline: it reaches past the left edge, it
                // overlaps the item at all, and it does not swallow the whole thing.
                check(cx - r < bounds[0],
                      name + " bite " + b + " does not reach the outline; it would punch"
                      + " a hole in the middle of the item");
                check(cx + r > bounds[0] + (bounds[2] - bounds[0]) * 0.2f,
                      name + " bite " + b + " barely touches the item");
                check(cx + r < bounds[0] + (bounds[2] - bounds[0]) * 0.75f,
                      name + " bite " + b + " takes too much of the item at once");
            }

            // The LAST bite is a sweep, and what matters is not that its circle spans the
            // item -- that was the first attempt and it was not enough -- but that the
            // ARC is flat enough over the item's height that nothing sticking out to the
            // left can slip past it. Measure the bow directly: how much further right the
            // cut sits at the item's middle than at its top and bottom.
            Art.biteCircle(bounds, Art.BITE_COUNT - 2, circle);
            float half = (bounds[3] - bounds[1]) * 0.5f;
            check(circle[2] > half,
                  name + " last bite is too small to reach across the item");
            float atMiddle = circle[0] + circle[2];
            float atEdge = circle[0] + (float) Math.sqrt(circle[2] * circle[2] - half * half);
            check(atMiddle - atEdge < (bounds[2] - bounds[0]) * 0.08f,
                  name + " last bite bows by " + (atMiddle - atEdge)
                  + " units across the item; a cut that curved can orphan a fragment");

            // Two bites must not be the same bite, or the middle state would look
            // identical to the last one.
            float[] first = new float[3];
            Art.biteCircle(bounds, 0, first);
            check(circle[0] + circle[2] > first[0] + first[2] + (bounds[2] - bounds[0]) * 0.04f,
                  name + " takes its second bite from behind the first, so nothing"
                  + " more appears to be missing");
        }

        // The engine's beat has to pass through every bite count and end with the item
        // gone, or a bite would be skipped on screen.
        FakeClock clock = new FakeClock();
        Engine e = new Engine(clock);
        e.setRoutine(tasks(3), keys(3));
        e.start(10 * 60);
        boolean[] seen = new boolean[Art.BITE_COUNT + 1];
        long segment = 10 * 60_000L / e.collectibleCount();
        long step = 20L;
        for (long at = segment - (long) (Engine.FEAST_LEAD * 1000f) - 100L;
             at < segment + (long) (Engine.FEAST_SECONDS * 1000f) + 200L; at += step) {
            e.reset();
            e.start(10 * 60);
            clock.advance(at);
            float beat = e.feastBeat();
            if (beat < 0f) continue;
            float eaten = (beat - 0.12f) / (0.82f - 0.12f) * Art.BITE_COUNT;
            int taken = eaten <= 0f ? 0 : Math.min((int) eaten, Art.BITE_COUNT);
            seen[taken] = true;
        }
        for (int b = 0; b <= Art.BITE_COUNT; b++) {
            check(seen[b], "the action beat never shows " + b + " bites taken");
        }
    }

    private static void checkShape(float[] shape, String what) {
        check(shape != null && shape.length > 0, what + " is empty");
        if (shape == null || shape.length == 0) return;

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        int i = 0;
        boolean started = false;
        while (i < shape.length) {
            int op = (int) shape[i];
            int operands = Art.operandCount(op);
            check(operands >= 0, what + " has an unknown opcode " + op + " at " + i);
            if (operands < 0) return;
            check(i + 1 + operands <= shape.length,
                  what + " is truncated: opcode " + op + " at " + i + " lacks operands");
            if (i + 1 + operands > shape.length) return;
            if (op == Art.MOVE || op == Art.CIRCLE || op == Art.HOLE
                || op == Art.OVAL || op == Art.RRECT) started = true;
            check(started || op == Art.CLOSE,
                  what + " draws before any move, circle, oval or round rect");

            // Control points bound the curve, so checking them is conservative.
            if (op == Art.CIRCLE || op == Art.HOLE) {
                float cx = shape[i + 1], cy = shape[i + 2], r = Math.abs(shape[i + 3]);
                check(!Float.isNaN(cx) && !Float.isNaN(cy) && !Float.isNaN(r),
                      what + " has a non-finite circle at " + i);
                // A HOLE only ever removes, so it lays no ink and is allowed to reach
                // outside the box -- a bite has to, since it straddles the outline.
                // The box check below is about where ink lands.
                if (op == Art.CIRCLE) {
                    minX = Math.min(minX, cx - r); maxX = Math.max(maxX, cx + r);
                    minY = Math.min(minY, cy - r); maxY = Math.max(maxY, cy + r);
                }
            } else {
                for (int k = 0; k < operands; k++) {
                    float v = shape[i + 1 + k];
                    check(!Float.isNaN(v) && !Float.isInfinite(v),
                          what + " has a non-finite coordinate at " + (i + 1 + k));
                    if (op == Art.RRECT && k >= 4) continue;       // corner radii, not points
                    if (k % 2 == 0) { minX = Math.min(minX, v); maxX = Math.max(maxX, v); }
                    else            { minY = Math.min(minY, v); maxY = Math.max(maxY, v); }
                }
            }
            i += 1 + operands;
        }
        check(i == shape.length, what + " has trailing data after the last command");

        // A little slack: the rim light is drawn inside the shape, but a form may sit
        // right on the edge of its box.
        check(minX >= -4f && maxX <= 104f,
              what + " runs outside its box horizontally: " + (int) minX + ".." + (int) maxX);
        check(minY >= -4f && maxY <= 104f,
              what + " runs outside its box vertically: " + (int) minY + ".." + (int) maxY);
        check(maxX - minX > 4f && maxY - minY > 4f,
              what + " is too small to be visible: " + (int) (maxX - minX) + "x" + (int) (maxY - minY));
    }

    // -------------------------------------------------------------- time formatting

    private static void timeFormatting() {
        check(TimeText.toText(0L).equals("0:00"), "zero should format as 0:00");
        check(TimeText.toText(-5000L).equals("0:00"), "negative time should clamp to 0:00");
        check(TimeText.toText(1000L).equals("0:01"), "one second");
        check(TimeText.toText(222_000L).equals("3:42"), "the frozen example");
        check(TimeText.toText(900_000L).equals("15:00"), "fifteen minutes");
        check(TimeText.toText(3_600_000L).equals("60:00"), "an hour");
        check(TimeText.toText(7_200_000L).equals("120:00"), "the longest selectable routine");
        // Part-seconds round up, so a countdown shows 1:00 rather than 0:59 for the last
        // moment of a minute -- the display never appears to skip the top of a minute.
        check(TimeText.toText(59_001L).equals("1:00"), "part-seconds should round up");
        check(TimeText.toText(59_999L).equals("1:00"), "just under a minute still reads 1:00");
        check(TimeText.toText(60_000L).equals("1:00"), "exactly a minute");

        char[] buf = new char[8];
        int len = TimeText.format(222_000L, buf);
        check(len == 4, "3:42 is four characters, got " + len);
        // The buffer must be reused, not reallocated, or the per-frame draw allocates.
        check(TimeText.BUFFER == TimeText.BUFFER, "the shared buffer identity must be stable");
        for (int i = 0; i < 1000; i++) TimeText.format(i * 731L);
        check(TimeText.BUFFER.length == 8, "the shared buffer must not be resized");
    }

    // ----------------------------------------------------------------------- engine

    /** A clock the test drives by hand, standing in for SystemClock. */
    private static final class FakeClock implements Engine.Clock {
        long now = 1_000_000L;
        public long nowMs() { return now; }
        void advance(long ms) { now += ms; }
    }

    private static final class Recorder implements Engine.Listener {
        int completions;
        int missionCompletes;
        int timeUps;
        long frozenAt = -1L;
        int lastIndex = -1;
        boolean lastWasFinal;

        public void onTaskCompleted(int index, boolean last) {
            completions++;
            lastIndex = index;
            lastWasFinal = last;
        }
        public void onMissionComplete(long remainingMs) {
            missionCompletes++;
            frozenAt = remainingMs;
        }
        public void onTimeUp() { timeUps++; }
    }

    private static String[] tasks(int n) {
        String[] out = new String[n];
        for (int i = 0; i < n; i++) out[i] = "Task " + i;
        return out;
    }

    private static String[] keys(int n) {
        String[] out = new String[n];
        for (int i = 0; i < n; i++) out[i] = "DRESS";
        return out;
    }

    private static void engineContract() {
        freezeContract();
        emptyRoutine();
        timeUpBehaviour();
        pauseBehaviour();
        collectibles();
        routineReconciliation();
    }

    /**
     * The behaviour a user would notice if it regressed: finish with 3:42 on the clock
     * and 3:42 is what stays on screen through the celebration.
     */
    private static void freezeContract() {
        FakeClock clock = new FakeClock();
        Recorder rec = new Recorder();
        Engine e = new Engine(clock);
        e.setListener(rec);
        e.setRoutine(tasks(5), keys(5));

        check(e.remainingMs() == 15L * 60_000L, "an idle engine should report the full duration");
        e.start(15 * 60);
        check(e.remainingMs() == 900_000L, "remaining should be 15:00 at the start");
        check(!e.allDone(), "a fresh routine is not complete");

        clock.advance(678_000L);                       // 11:18 gone, 3:42 left
        check(e.remainingMs() == 222_000L,
              "remaining should be 3:42, was " + e.remainingMs());

        for (int i = 0; i < 4; i++) {
            check(e.completeActive(), "task " + i + " should complete");
            check(!e.allDone(), "not complete until every task is done");
        }
        check(e.completeActive(), "the final task should complete");
        check(e.allDone(), "every task is done, so the mission is complete");
        check(e.completionRemainingMs() == 222_000L,
              "the clock should freeze at 3:42, froze at " + e.completionRemainingMs());

        // The whole point: time keeps passing, the displayed value does not.
        clock.advance(60_000L);
        check(e.remainingMs() == 222_000L,
              "the frozen time must not tick down during the celebration, was " + e.remainingMs());
        clock.advance(10L * 60_000L);
        check(e.remainingMs() == 222_000L,
              "the frozen time must survive running past the original end");
        check(TimeText.toText(e.remainingMs()).equals("3:42"),
              "the frozen time should format as 3:42, got " + TimeText.toText(e.remainingMs()));

        check(rec.completions == 5, "five completions should have been reported");
        check(rec.missionCompletes == 1, "mission complete should fire exactly once");
        check(rec.frozenAt == 222_000L, "the listener should receive the frozen time");
        check(rec.lastIndex == 4 && rec.lastWasFinal, "the last completion should be flagged final");
        check(rec.timeUps == 0, "time up must not fire when the routine finished in time");

        check(!e.completeActive(), "completing again after finishing should do nothing");
        check(rec.completions == 5, "a no-op completion must not notify");

        e.reset();
        check(!e.allDone(), "reset should clear completion");
        check(e.completionRemainingMs() == -1L, "reset should clear the frozen time");
        check(e.remainingMs() == 900_000L, "reset should restore the full duration");
        check(!e.isRunning(), "reset should stop the countdown");
    }

    private static void emptyRoutine() {
        FakeClock clock = new FakeClock();
        Engine e = new Engine(clock);
        e.setRoutine(new String[0], new String[0]);
        e.start(10 * 60);
        check(!e.allDone(), "an empty routine must never report itself complete");
        check(!e.completeActive(), "an empty routine has nothing to complete");
        check(e.taskCount() == 0, "an empty routine has no tasks");
        check(e.progress() >= 0f && e.progress() <= 1f, "progress must stay in range when empty");
    }

    private static void timeUpBehaviour() {
        FakeClock clock = new FakeClock();
        Recorder rec = new Recorder();
        Engine e = new Engine(clock);
        e.setListener(rec);
        e.setRoutine(tasks(3), keys(3));
        e.start(1 * 60);

        check(!e.pollTimeUp(), "time up must not fire while time remains");
        clock.advance(61_000L);
        check(e.isTimeUp(), "the countdown should have reached zero");
        check(e.pollTimeUp(), "time up should fire once zero is reached");
        check(!e.pollTimeUp(), "time up must fire only once");
        check(rec.timeUps == 1, "the listener should have seen exactly one time up");
        check(e.remainingMs() == 0L, "remaining should clamp at zero, not go negative");
        check(!e.allDone(), "running out of time is not finishing the routine");

        // Finishing after time ran out freezes at zero rather than a negative number.
        e.completeActive(); e.completeActive(); e.completeActive();
        check(e.allDone(), "tasks can still be completed after time is up");
        check(e.completionRemainingMs() == 0L,
              "finishing late should freeze at 0:00, got " + e.completionRemainingMs());
    }

    private static void pauseBehaviour() {
        FakeClock clock = new FakeClock();
        Engine e = new Engine(clock);
        e.setRoutine(tasks(3), keys(3));
        e.start(10 * 60);
        clock.advance(120_000L);
        check(e.remainingMs() == 480_000L, "eight minutes should be left before pausing");

        e.pause();
        check(e.isPaused(), "the engine should report itself paused");
        clock.advance(300_000L);
        check(e.remainingMs() == 480_000L,
              "a paused countdown must not run down, was " + e.remainingMs());

        e.resume();
        check(!e.isPaused(), "resume should clear the paused state");
        clock.advance(60_000L);
        check(e.remainingMs() == 420_000L,
              "the countdown should resume from where it paused, was " + e.remainingMs());

        e.pause();
        e.completeActive(); e.completeActive(); e.completeActive();
        check(e.allDone() && e.completionRemainingMs() == 420_000L,
              "finishing while paused should freeze the paused time");
    }

    /**
     * The duration table behind the time picker's slider and steppers.
     *
     * <p>Its whole job is that every value the controls can land on is one a person
     * would choose, and that the two controls agree: the slider maps a position to an
     * index, the steppers move by one index, and both must stay inside the table.
     */
    private static void durationStops() {
        int n = ScreenTimePicker.stopCount();
        check(n > 100, "the duration table is suspiciously short: " + n);
        check(ScreenTimePicker.stopAt(0) == MorningView.MIN_DURATION_SECONDS,
              "the table should start at the shortest allowed morning");
        check(ScreenTimePicker.stopAt(n - 1) == MorningView.MAX_DURATION_SECONDS,
              "the table should end at the longest allowed morning");

        for (int i = 1; i < n; i++) {
            check(ScreenTimePicker.stopAt(i) > ScreenTimePicker.stopAt(i - 1),
                  "the duration table is not strictly increasing at " + i);
        }

        // A stop must resolve to itself, and stepping up then down must come back to
        // where it started -- otherwise + and - would drift the value.
        for (int i = 0; i < n; i++) {
            int seconds = ScreenTimePicker.stopAt(i);
            check(ScreenTimePicker.stopIndex(seconds) == i,
                  "stop " + seconds + "s does not resolve back to its own index");
            if (i < n - 1) {
                int up = ScreenTimePicker.stopAt(ScreenTimePicker.stopIndex(seconds) + 1);
                int back = ScreenTimePicker.stopAt(ScreenTimePicker.stopIndex(up) - 1);
                check(back == seconds,
                      "stepping up from " + seconds + "s and back gave " + back + "s");
            }
        }

        // Anything off the table -- a stored value, a slider landing between stops --
        // has to clamp onto one rather than throw or run off the end.
        for (int seconds : new int[]{-5, 0, 1, 14, 17, 61, 3607, 99999}) {
            int index = ScreenTimePicker.stopIndex(seconds);
            check(index >= 0 && index < n,
                  seconds + "s resolved to an out-of-range index " + index);
        }

        // Sub-minute durations are the point of the exercise.
        check(ScreenTimePicker.stopAt(ScreenTimePicker.stopIndex(30)) == 30,
              "thirty seconds should be settable");
        check(TimeText.describe(30).equals("30 sec"), "under a minute reads as seconds");
        check(TimeText.describe(900).equals("15 min"), "a whole quarter hour reads as minutes");
        check(TimeText.describe(90).equals("1:30"), "a mixed duration reads as m:ss");
    }

    private static void collectibles() {
        durationStops();

        FakeClock clock = new FakeClock();
        Engine e = new Engine(clock);
        e.setRoutine(tasks(5), keys(5));

        // Roughly one every three minutes, floored at three and capped at twenty-four.
        // Every treat is shown on the board, so a long morning really does carry a lot.
        int[][] expected = {{1, 3}, {5, 5}, {12, 7}, {15, 8}, {30, 13}, {60, 23}, {94, 24},
                            {120, 24}};
        for (int[] pair : expected) {
            e.reset();
            e.start(pair[0] * 60);
            check(e.collectibleCount() == pair[1],
                  pair[0] + " minutes should show " + pair[1] + " collectibles, showed "
                  + e.collectibleCount());
            e.reset();
        }

        // A longer morning must never offer fewer things to find than a shorter one.
        int previousCount = 0;
        for (int minutes = 1; minutes <= 120; minutes++) {
            e.reset();
            e.start(minutes * 60);
            int n = e.collectibleCount();
            check(n >= previousCount,
                  "collectible count went backwards at " + minutes + " minutes");
            check(n >= Engine.MIN_COLLECTIBLES && n <= Engine.MAX_COLLECTIBLES,
                  minutes + " minutes gave an out-of-range collectible count: " + n);
            previousCount = n;
            e.reset();
        }

        e.reset();
        e.start(12 * 60);
        check(e.collectedCount() == 0, "nothing is collected at the start");
        int previous = 0;
        for (int minute = 1; minute <= 12; minute++) {
            clock.advance(60_000L);
            int now = e.collectedCount();
            check(now >= previous, "collected count must never go backwards");
            check(now <= e.collectibleCount(), "collected must never exceed the total");
            previous = now;
        }
        check(previous == e.collectibleCount(), "everything should be collected by the end");

        float f = e.collectibleFraction();
        check(f >= 0f && f < 1.0001f, "the collectible fraction must stay in range");

        feastBeat();
        biteEvents();
        clockCues();
    }

    /**
     * {@link Engine#pollBite}, which is what turns eating into a sound.
     *
     * <p>Three properties, and every one of them is a defect you would ship without
     * noticing: exactly three bites per treat (four crunches per treat, or two, is not
     * something a compiler objects to), each reported once (a poll that returned the
     * same bite on every frame of the window would fire fifty times a treat and sound
     * like static), and in order 0, 1, 2 (the pitch rises across them, so out of order
     * is audible as a wrong note).
     */
    private static void biteEvents() {
        for (int minutes : new int[]{1, 5, 15, 94}) {
            FakeClock clock = new FakeClock();
            Engine e = new Engine(clock);
            e.setRoutine(tasks(2), keys(2));
            e.start(minutes * 60);

            int total = e.collectibleCount();
            long durationMs = minutes * 60_000L;
            int[] perTreat = new int[total + 2];
            int expectedNext = 0;
            int treat = 0;

            // 60fps for the whole morning, which is the rate it will really be polled at.
            for (long at = 0; at <= durationMs; at += 16L) {
                int bite = e.pollBite();
                if (bite < 0) { clock.advance(16L); continue; }
                check(bite >= 0 && bite < Art.BITE_COUNT,
                      minutes + " minutes: pollBite returned " + bite);
                if (bite == 0) {
                    treat++;
                    expectedNext = 0;
                }
                check(bite == expectedNext,
                      minutes + " minutes: bites arrived out of order, got " + bite
                      + " expecting " + expectedNext);
                expectedNext++;
                if (treat >= 1 && treat < perTreat.length) perTreat[treat]++;
                clock.advance(16L);
            }

            // Every treat the morning counted as collected must have been eaten in
            // exactly BITE_COUNT goes. The last one can land right on the final tick, so
            // allow the run to end mid-treat -- but not to under-report an earlier one.
            int complete = 0;
            for (int i = 1; i <= treat; i++) {
                if (i < treat) {
                    check(perTreat[i] == Art.BITE_COUNT,
                          minutes + " minutes: treat " + i + " fired " + perTreat[i]
                          + " bites, expected " + Art.BITE_COUNT);
                }
                if (perTreat[i] == Art.BITE_COUNT) complete++;
            }
            check(complete >= total - 1,
                  minutes + " minutes: only " + complete + " of " + total
                  + " treats were fully eaten");
        }

        // Paused, nothing is being eaten, so nothing may fire -- otherwise the crunches
        // would carry on behind the paused veil.
        FakeClock clock = new FakeClock();
        Engine e = new Engine(clock);
        e.setRoutine(tasks(1), keys(1));
        e.start(5 * 60);
        clock.advance(31_000L);                        // partway into a treat
        e.pause();
        for (int i = 0; i < 200; i++) {
            check(e.pollBite() < 0, "a paused morning must not keep crunching");
            clock.advance(16L);
        }
    }

    /**
     * The halfway chime and the last-ten-seconds tick.
     *
     * <p>Both are cues a person notices only by their absence or by their excess: a
     * chime that fires twice, or forty ticks in the last ten seconds, is not something
     * a compiler or a screenshot has any opinion about.
     */
    private static void clockCues() {
        for (int minutes : new int[]{1, 5, 15, 94}) {
            FakeClock clock = new FakeClock();
            Engine e = new Engine(clock);
            e.setRoutine(tasks(2), keys(2));
            e.start(minutes * 60);

            int chimes = 0;
            int ticks = 0;
            int previousSecond = Integer.MAX_VALUE;
            long durationMs = minutes * 60_000L;
            for (long at = 0; at <= durationMs + 2_000L; at += 16L) {
                if (e.pollMilestone()) {
                    chimes++;
                    check(e.progress() >= 0.5f,
                          minutes + " minutes: the halfway chime rang at "
                          + (int) (e.progress() * 100) + "%");
                }
                int second = e.pollTick();
                if (second > 0) {
                    ticks++;
                    check(second <= Engine.TICK_SECONDS,
                          minutes + " minutes: tick reported second " + second);
                    check(second < previousSecond,
                          minutes + " minutes: ticks are not counting down, "
                          + second + " after " + previousSecond);
                    previousSecond = second;
                }
                clock.advance(16L);
            }
            check(chimes == 1,
                  minutes + " minutes: the halfway chime rang " + chimes + " times");
            check(ticks == Engine.TICK_SECONDS,
                  minutes + " minutes: " + ticks + " ticks in the last "
                  + Engine.TICK_SECONDS + " seconds");
        }

        // Paused, the clock is not running, so neither cue may fire.
        FakeClock clock = new FakeClock();
        Engine e = new Engine(clock);
        e.setRoutine(tasks(1), keys(1));
        e.start(60);
        clock.advance(55_000L);                        // inside the ticking window
        e.pause();
        for (int i = 0; i < 300; i++) {
            check(!e.pollMilestone(), "a paused morning must not chime");
            check(e.pollTick() < 0, "a paused morning must not tick");
            clock.advance(16L);
        }

        // And a finished morning goes quiet: the victory flourish owns that moment.
        e.resume();
        e.completeActive();
        check(e.allDone(), "the routine should be finished");
        for (int i = 0; i < 300; i++) {
            check(e.pollTick() < 0, "a finished morning must not keep ticking");
            clock.advance(16L);
        }
    }

    /**
     * The action beat has to run at a fixed speed in seconds, not as a fraction of a
     * segment, or a ninety-minute morning would show a chomp in slow motion.
     */
    private static void feastBeat() {
        FakeClock clock = new FakeClock();
        Engine e = new Engine(clock);
        e.setRoutine(tasks(3), keys(3));

        // Includes the shortest timer the picker can now set, where a segment is only a
        // few seconds and the two-second eating beat has the least room.
        for (int seconds : new int[]{MorningView.MIN_DURATION_SECONDS, 120, 600, 1800, 5400}) {
            e.reset();
            e.start(seconds);
            int total = e.collectibleCount();
            long segment = seconds * 1000L / total;
            int minutes = seconds / 60;
            check(segment > (long) (Engine.FEAST_SECONDS * 1000f),
                  minutes + " minutes packs collectibles closer than one action beat");

            check(e.feastBeat() < 0f || e.feastBeat() <= 1f,
                  "the beat is either idle or inside 0..1 at the start");

            // Walk to just before the first item, then across it.
            long toFirst = segment;
            clock.advance(toFirst - (long) (Engine.FEAST_LEAD * 1000f) + 10L);
            float lead = e.feastBeat();
            check(lead >= 0f && lead < 1f,
                  minutes + " minutes: the wind-up should have started, beat=" + lead);
            check(e.collectedCount() == 0, "the item is not collected during the wind-up");

            clock.advance((long) (Engine.FEAST_LEAD * 1000f));
            check(e.collectedCount() == 1, "the item is collected on arrival");
            float contact = e.feastBeat();
            check(contact >= 0f && contact <= 1f,
                  minutes + " minutes: the beat continues through contact, beat=" + contact);
            check(contact > lead, "the beat must advance across the item");

            // Well past the beat, the buddy is walking again.
            clock.advance((long) (Engine.FEAST_SECONDS * 1000f) + 500L);
            check(e.feastBeat() < 0f,
                  minutes + " minutes: the beat should have ended, beat=" + e.feastBeat());
            check(e.secondsSinceCollected() > Engine.FEAST_SECONDS,
                  "seconds-since should keep counting up after the beat");
            e.reset();
        }

        // Before the first item there is nothing to have just eaten.
        e.reset();
        e.start(10 * 60);
        check(e.secondsSinceCollected() == Float.MAX_VALUE,
              "nothing has been collected yet at the start");
        check(e.secondsUntilCollect() < Float.MAX_VALUE,
              "the first item is still ahead at the start");
    }

    private static void routineReconciliation() {
        FakeClock clock = new FakeClock();
        Engine e = new Engine(clock);
        // A preference written by an older version can have mismatched name and key
        // lists; that must reconcile rather than throw.
        e.setRoutine(new String[]{"A", "B", "C"}, new String[]{"EAT"});
        check(e.taskCount() == 3, "names should determine the task count");
        check(e.taskKey(0).equals("EAT"), "an existing key should be kept");
        check(!e.taskKey(2).isEmpty(), "a missing key should get a default");
        e.setRoutine(null, null);
        check(e.taskCount() == 0, "null routine arrays should be treated as empty");
        check(e.taskName(-1).isEmpty() && e.taskName(99).isEmpty(),
              "out-of-range task lookups must not throw");
    }

    // ---------------------------------------------------------------------- hit map

    private static void hitMapBasics() {
        HitMap h = new HitMap();
        RectF a = new RectF(0, 0, 100, 100);
        RectF b = new RectF(50, 50, 150, 150);
        h.clear();
        h.add(1, a);
        h.add(2, b);
        check(h.idAt(h.hit(10, 10)) == 1, "hit should find the first region");
        check(h.idAt(h.hit(120, 120)) == 2, "hit should find the second region");
        check(h.idAt(h.hit(60, 60)) == 2, "the later region must win where they overlap");
        check(h.hit(500, 500) == HitMap.NONE, "a miss must return NONE");

        h.clear();
        check(h.size() == 0, "clear should empty the map");

        RectF tiny = new RectF(10, 10, 20, 20);
        h.addPadded(3, tiny, 100f, 0);
        check(h.rectAt(0).width() >= 100f && h.rectAt(0).height() >= 100f,
              "addPadded should grow a small target");
        check(Math.abs(h.rectAt(0).centerX() - tiny.centerX()) < 0.01f,
              "addPadded must keep the target centred");

        h.clear();
        RectF clip = new RectF(0, 0, 100, 100);
        h.addClipped(4, new RectF(0, 10, 100, 60), clip, 0);
        h.addClipped(5, new RectF(0, 200, 100, 260), clip, 0);
        check(h.size() == 1, "a row scrolled out of its viewport must not stay tappable");

        // Every screen must fit in the map with headroom to spare.
        check(h.capacity() >= 40, "HitMap capacity is too small for the busiest screen");
    }
}
