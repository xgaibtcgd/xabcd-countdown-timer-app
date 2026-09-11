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
                for (int tasks = 1; tasks <= Layout.MAX_TASK_ROWS; tasks++) {
                    L.measure(screen[0], screen[1], inset[0], inset[1], screen[2], tasks);
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
