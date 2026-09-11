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
        RectF[] stack = {L.advClock, L.advRibbon, L.advScene, L.advTaskCard, L.advAction};
        String[] names = {"advClock", "advRibbon", "advScene", "advTaskCard", "advAction"};
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
        check(L.advTrail.right <= L.advGoal.left + 0.6f,
              "the buddy's lane runs into the goal @ " + at);

        float minTouch = L.minTouchUnits();
        check(L.advAction.height() >= minTouch - 0.6f, "advAction under touch target @ " + at);
        check(L.advPause.width() >= minTouch - 0.6f, "advPause under touch target @ " + at);
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

        check(BuddyTheme.of(3).collectibleNoun(1).equals("fish"), "shark singular noun");
        check(BuddyTheme.of(3).collectibleNoun(4).equals("fish"), "shark plural noun");
        check(BuddyTheme.of(4).collectibleNoun(1).equals("leaf"), "dino singular noun");
        check(BuddyTheme.of(4).collectibleNoun(4).equals("leaves"), "dino plural noun");
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

        for (int g = 0; g < Art.GLYPH_COUNT; g++) {
            check(Art.GLYPHS[g] != null && Art.GLYPHS[g].length > 0,
                  "UI glyph " + g + " is missing");
            checkShape(Art.GLYPHS[g], "glyph " + g);
        }
    }

    /** Validates the command stream and the bounding box of one shape. */
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
                minX = Math.min(minX, cx - r); maxX = Math.max(maxX, cx + r);
                minY = Math.min(minY, cy - r); maxY = Math.max(maxY, cy + r);
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
        e.start(15);
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
        e.start(10);
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
        e.start(1);

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
        e.start(10);
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

    private static void collectibles() {
        FakeClock clock = new FakeClock();
        Engine e = new Engine(clock);
        e.setRoutine(tasks(5), keys(5));

        int[][] expected = {{1, 1}, {5, 5}, {12, 12}, {15, 12}, {60, 12}, {120, 12}};
        for (int[] pair : expected) {
            e.reset();
            e.start(pair[0]);
            check(e.collectibleCount() == pair[1],
                  pair[0] + " minutes should show " + pair[1] + " collectibles, showed "
                  + e.collectibleCount());
            e.reset();
        }

        e.reset();
        e.start(12);
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
