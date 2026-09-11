package com.morningmission.app;

import android.graphics.RectF;

/**
 * Where everything goes, for every screen, recomputed whenever the view is resized.
 *
 * <p>The old build positioned everything with absolute constants anchored to the top
 * inset -- {@code ts+330}, {@code ts+465}, {@code ts+690} -- which assumed a 1080x1920
 * screen. On a 1080x2400 phone the middle of the layout does not stretch, so bands drift
 * apart, and the clamp that papered over it could push Start Morning underneath the
 * navigation bar. Worse, {@code onTouchEvent} carried its own copy of those numbers and
 * the two copies had already diverged: task rows were drawn at a pitch of 115 but
 * hit-tested at 120, and half the Edit chip was unreachable because the timer's touch
 * region overlapped it.
 *
 * <p>Here each screen is a vertical stack of bands with a minimum, a maximum and grow and
 * shrink weights, and a solver hands out the slack. Screens read the resulting rectangles
 * to draw, and register those same rectangles with {@link HitMap} to be tapped, so the
 * two can no longer disagree.
 *
 * <p>X is expressed in a fixed 1080-unit design space and scaled to the real width, which
 * is the one part of the old approach that worked. Y is solved.
 */
final class Layout {

    /** Design width. All coordinates are in these units; the canvas is scaled to fit. */
    static final float W = 1080f;

    static final int MAX_TASK_ROWS = 12;
    static final int NAV_ITEMS = 4;

    /** Task rows are a fixed height so they never shrink below a comfortable tap target. */
    static final float TASK_ROW_H = 168f;
    static final float TASK_ROW_GAP = 18f;
    static final float TASK_ROW_PITCH = TASK_ROW_H + TASK_ROW_GAP;

    /** Android's minimum recommended touch target. */
    private static final float MIN_TOUCH_DP = 48f;

    /**
     * Size of a round chip control: back, pause, the gear, the time steppers.
     *
     * <p>50dp on a 360dp-wide phone. The old build drew these smaller than the 48dp
     * minimum and compensated by hit-testing a larger invisible area, which is how the
     * drawn and tapped geometry drifted apart in the first place. Here the drawn chip is
     * the tap target.
     */
    static final float CHIP = 150f;

    // ------------------------------------------------------------------- viewport

    /** Pixels per design unit. */
    float scale = 1f;
    /** Height of the view in design units. */
    float height = 1920f;
    float safeTop, safeBottom;
    /** Width of the screen in dp, used only to sanity-check touch target sizes. */
    float widthDp = 360f;

    /** The area between the system insets: the whole usable canvas. */
    final RectF play = new RectF();

    // ----------------------------------------------------------------------- home

    final RectF homeHeader = new RectF();
    final RectF gearChip = new RectF();
    final RectF grownUpsChip = new RectF();
    final RectF wordmark = new RectF();
    final RectF hero = new RectF();
    final RectF buddySlot = new RectF();
    final RectF minutesLabel = new RectF();
    final RectF timerCard = new RectF();
    final RectF timerPencil = new RectF();
    final RectF routineHeader = new RectF();
    final RectF editChip = new RectF();
    /** Scrolling viewport for the task list. */
    final RectF taskBand = new RectF();
    final RectF startBtn = new RectF();
    final RectF navBar = new RectF();
    final RectF[] minuteBubble = newRects(7);
    final RectF[] navItem = newRects(NAV_ITEMS);
    /** Row rectangles, already offset by the scroll position. */
    final RectF[] taskRow = newRects(MAX_TASK_ROWS);
    int taskRowCount;
    /** Total content height of the task list, for clamping the scroll. */
    float taskContentHeight;
    private float taskScroll;

    // ------------------------------------------------------------------ adventure

    final RectF advBack = new RectF();
    final RectF advTitle = new RectF();
    final RectF advPause = new RectF();
    final RectF advClock = new RectF();
    final RectF advRibbon = new RectF();
    /** The illustrated world: everything between the clock and the task card. */
    final RectF advScene = new RectF();
    /** The horizontal lane the buddy travels along. */
    final RectF advTrail = new RectF();
    final RectF advGoal = new RectF();
    final RectF advProgress = new RectF();
    final RectF advTaskCard = new RectF();
    final RectF advAction = new RectF();

    // --------------------------------------------------------------------- finish

    final RectF cmpTitle = new RectF();
    final RectF cmpStage = new RectF();
    final RectF cmpCard = new RectF();
    final RectF cmpPlayAgain = new RectF();
    final RectF cmpBackHome = new RectF();

    // --------------------------------------------------------------- buddy picker

    final RectF pickSheet = new RectF();
    final RectF pickTitle = new RectF();
    final RectF pickClose = new RectF();
    final RectF pickGrid = new RectF();
    final RectF pickConfirm = new RectF();
    final RectF[] buddyCard = newRects(BuddyTheme.COUNT);

    // ---------------------------------------------------------------- time picker

    final RectF timeSheet = new RectF();
    final RectF timeTitle = new RectF();
    final RectF timeClose = new RectF();
    final RectF timeDisplay = new RectF();
    final RectF timeMinus = new RectF();
    final RectF timePlus = new RectF();
    final RectF timeSlider = new RectF();
    final RectF timeSet = new RectF();
    final RectF[] presetBubble = newRects(7);

    // ------------------------------------------------------------------ grown-ups

    static final int GROWN_UP_ROWS = 8;
    final RectF guBack = new RectF();
    final RectF guTitle = new RectF();
    final RectF guPanel = new RectF();
    final RectF guUnlock = new RectF();
    /** Scrolling viewport: seven 48dp rows do not fit a small screen with large insets. */
    final RectF guBand = new RectF();
    final RectF[] guRow = newRects(GROWN_UP_ROWS);
    float guContentHeight;
    private float guScroll;

    // ------------------------------------------------------------- routine editor

    final RectF edBack = new RectF();
    final RectF edTitle = new RectF();
    final RectF edBand = new RectF();
    final RectF edAdd = new RectF();
    final RectF edSave = new RectF();
    final RectF[] edRow = newRects(MAX_TASK_ROWS);
    int edRowCount;
    float edContentHeight;
    private float edScroll;

    /**
     * Kept local rather than shared with {@link Theme}: Layout must not touch any class
     * that loads native graphics state, so that the layout sweep in tools/SelfTest.java
     * can run off-device. Theme's static initialiser reaches Typeface, which is native.
     */
    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static RectF[] newRects(int n) {
        RectF[] out = new RectF[n];
        for (int i = 0; i < n; i++) out[i] = new RectF();
        return out;
    }

    /** Smallest side, in design units, that still meets the 48dp touch target. */
    float minTouchUnits() {
        return MIN_TOUCH_DP * W / Math.max(1f, widthDp);
    }

    // ----------------------------------------------------------------- band solver
    //
    // Each band declares a minimum, a maximum, and how eagerly it takes or gives up
    // space. Growth is capped at the maximum and the surplus redistributed; shrink is
    // floored. Any residue that nothing will accept goes to the spill band, which keeps
    // the bottom-most band pinned to the bottom of the screen.

    private static final int MAX_BANDS = 28;
    private final float[] bandMin = new float[MAX_BANDS];
    private final float[] bandMax = new float[MAX_BANDS];
    private final float[] bandGrow = new float[MAX_BANDS];
    private final float[] bandShrink = new float[MAX_BANDS];
    private final float[] bandSize = new float[MAX_BANDS];
    private final float[] bandTop = new float[MAX_BANDS];
    private int bandCount;
    private int spillBand;

    private void begin() {
        bandCount = 0;
        spillBand = -1;
    }

    /** Adds a band; returns its index. */
    private int band(float min, float max, float grow, float shrink) {
        int i = bandCount++;
        bandMin[i] = Math.max(0f, min);
        bandMax[i] = Math.max(bandMin[i], max);
        bandGrow[i] = grow;
        bandShrink[i] = shrink;
        return i;
    }

    /** Adds a flexible gap. */
    private int gap() { return band(16f, 46f, 1f, 1f); }

    /** Adds a band that never changes size. */
    private int fixed(float size) { return band(size, size, 0f, 0f); }

    private void solve(float top, float available) {
        float total = 0f;
        for (int i = 0; i < bandCount; i++) {
            bandSize[i] = bandMin[i];
            total += bandMin[i];
        }
        float slack = available - total;

        if (slack > 0f) {
            // Grow, capping at the maximum and re-offering the surplus to whatever is
            // still under its cap. Four passes settles every stack we define.
            for (int pass = 0; pass < 4 && slack > 0.5f; pass++) {
                float weight = 0f;
                for (int i = 0; i < bandCount; i++) {
                    if (bandGrow[i] > 0f && bandSize[i] < bandMax[i]) weight += bandGrow[i];
                }
                if (weight <= 0f) break;
                float remaining = slack;
                for (int i = 0; i < bandCount; i++) {
                    if (bandGrow[i] <= 0f || bandSize[i] >= bandMax[i]) continue;
                    float want = slack * bandGrow[i] / weight;
                    float take = Math.min(want, bandMax[i] - bandSize[i]);
                    bandSize[i] += take;
                    remaining -= take;
                }
                slack = remaining;
            }
        } else if (slack < 0f) {
            // Shrink by weight. A band never goes below 62% of its minimum, and bands
            // with no shrink weight (chips, the nav bar) never move at all.
            for (int pass = 0; pass < 4 && slack < -0.5f; pass++) {
                float weight = 0f;
                for (int i = 0; i < bandCount; i++) {
                    if (bandShrink[i] > 0f && bandSize[i] > bandMin[i] * 0.62f) weight += bandShrink[i];
                }
                if (weight <= 0f) break;
                float remaining = slack;
                for (int i = 0; i < bandCount; i++) {
                    if (bandShrink[i] <= 0f) continue;
                    float floor = bandMin[i] * 0.62f;
                    if (bandSize[i] <= floor) continue;
                    float want = -slack * bandShrink[i] / weight;
                    float give = Math.min(want, bandSize[i] - floor);
                    bandSize[i] -= give;
                    remaining += give;
                }
                slack = remaining;
            }
        }

        if (slack > 0.5f && spillBand >= 0) bandSize[spillBand] += slack;

        float y = top;
        for (int i = 0; i < bandCount; i++) {
            bandTop[i] = y;
            y += bandSize[i];
        }
    }

    /** Fills {@code out} with band {@code i} inset horizontally. */
    private void place(int i, RectF out, float left, float right) {
        out.set(left, bandTop[i], right, bandTop[i] + bandSize[i]);
    }

    private float bandBottom(int i) { return bandTop[i] + bandSize[i]; }

    // --------------------------------------------------------------------- measure

    /**
     * Recomputes every rectangle. Call from {@code onSizeChanged}, never from
     * {@code onDraw}.
     *
     * <p>Scrolling is deliberately not an input here: a scroll should not re-solve every
     * band on every frame of a fling. Call {@link #scrollTasks}, {@link #scrollGrownUps}
     * or {@link #scrollEditor} instead, which move only the row rectangles.
     *
     * @param taskCount number of tasks in the routine, which sets the task list height
     * @param editorRowCount rows the routine editor is currently showing, which can differ
     *                       from {@code taskCount} while edits are unsaved
     */
    void measure(int widthPx, int heightPx, int insetTopPx, int insetBottomPx,
                 float densityDpi, int taskCount, int editorRowCount) {
        scale = widthPx <= 0 ? 1f : widthPx / W;
        height = heightPx / scale;
        safeTop = insetTopPx / scale + 18f;
        safeBottom = insetBottomPx / scale + 18f;
        widthDp = densityDpi <= 0f ? 360f : widthPx / (densityDpi / 160f);
        play.set(0f, safeTop, W, height - safeBottom);

        measureHome(taskCount);
        measureAdventure();
        measureComplete();
        measureBuddyPicker();
        measureTimePicker();
        measureGrownUps();
        measureRoutineEditor(editorRowCount);
    }

    // ------------------------------------------------------------------------ home

    private void measureHome(int taskCount) {
        taskRowCount = Math.max(0, Math.min(taskCount, MAX_TASK_ROWS));
        taskContentHeight = Math.max(0f, taskRowCount * TASK_ROW_PITCH - TASK_ROW_GAP);

        begin();
        int bHeader = fixed(176f);
        int g1 = gap();
        int bWordmark = band(200f, 250f, 1f, 0.5f);
        int g2 = gap();
        int bHero = band(380f, 640f, 4f, 2f);
        int g3 = gap();
        int bTimer = band(175f, 205f, 0.5f, 1f);
        int g4 = gap();
        int bRoutine = fixed(70f);
        int g5 = band(10f, 20f, 0.3f, 1f);
        // The list scrolls, so its band is a viewport: it may show as little as two
        // rows on a short screen rather than squeezing rows below a usable size.
        int bTasks = band(Math.min(taskContentHeight, TASK_ROW_PITCH * 2.2f),
                          Math.max(TASK_ROW_PITCH * 2.2f, taskContentHeight),
                          3f, 4f);
        int g6 = gap();
        int bStart = band(150f, 175f, 0.5f, 0f);
        int g7 = gap();
        int bNav = fixed(150f);
        spillBand = bHero;
        solve(play.top, play.height());

        place(bHeader, homeHeader, 40f, W - 40f);
        gearChip.set(homeHeader.left, homeHeader.centerY() - CHIP / 2f,
                     homeHeader.left + CHIP, homeHeader.centerY() + CHIP / 2f);
        grownUpsChip.set(W - 40f - 280f, homeHeader.centerY() - CHIP / 2f,
                         W - 40f, homeHeader.centerY() + CHIP / 2f);

        place(bWordmark, wordmark, 0f, W);
        place(bHero, hero, 0f, W);
        // Buddy on the left, minute bubbles on the right, as in the mockup.
        float buddyW = Math.min(hero.height() * 0.92f, 430f);
        buddySlot.set(hero.left + 30f, hero.top, hero.left + 30f + buddyW, hero.bottom);
        layoutMinuteBubbles(buddySlot.right + 10f, hero.top + 44f, W - 40f, hero.bottom - 10f);
        minutesLabel.set(buddySlot.right + 10f, hero.top, W - 40f, hero.top + 44f);

        place(bTimer, timerCard, 60f, W - 60f);
        float pencil = Math.min(96f, timerCard.height() * 0.55f);
        timerPencil.set(timerCard.right - 40f - pencil, timerCard.centerY() - pencil / 2f,
                        timerCard.right - 40f, timerCard.centerY() + pencil / 2f);

        place(bRoutine, routineHeader, 56f, W - 56f);
        float editW = 200f;
        editChip.set(routineHeader.right - editW, routineHeader.top + 4f,
                     routineHeader.right, routineHeader.bottom - 4f);

        place(bTasks, taskBand, 48f, W - 48f);
        scrollTasks(taskScroll);

        place(bStart, startBtn, 100f, W - 100f);
        place(bNav, navBar, 28f, W - 28f);
        float itemW = navBar.width() / NAV_ITEMS;
        for (int i = 0; i < NAV_ITEMS; i++) {
            navItem[i].set(navBar.left + i * itemW, navBar.top,
                           navBar.left + (i + 1) * itemW, navBar.bottom);
        }
        // Silence "unused" on the gap handles; they exist to occupy solver slots.
        touch(g1, g2, g3, g4, g5, g6, g7);
    }

    // ----------------------------------------------------------------------- scroll
    //
    // Rows sit at a fixed pitch inside a viewport. Keeping the height fixed and letting
    // the list scroll is what guarantees a comfortable tap target on a short screen,
    // rather than squeezing rows until they are too small to hit.

    private static float maxScroll(float content, float viewport) {
        return Math.max(0f, content - viewport);
    }

    float maxTaskScroll()     { return maxScroll(taskContentHeight, taskBand.height()); }
    float maxGrownUpsScroll() { return maxScroll(guContentHeight, guBand.height()); }
    float maxEditorScroll()   { return maxScroll(edContentHeight, edBand.height()); }

    /** Moves the task rows. Returns the clamped scroll actually applied. */
    float scrollTasks(float scroll) {
        taskScroll = clamp(scroll, 0f, maxTaskScroll());
        for (int i = 0; i < taskRowCount; i++) {
            float top = taskBand.top + i * TASK_ROW_PITCH - taskScroll;
            taskRow[i].set(taskBand.left, top, taskBand.right, top + TASK_ROW_H);
        }
        return taskScroll;
    }

    float scrollGrownUps(float scroll) {
        guScroll = clamp(scroll, 0f, maxGrownUpsScroll());
        for (int i = 0; i < GROWN_UP_ROWS; i++) {
            float top = guBand.top + i * TASK_ROW_PITCH - guScroll;
            guRow[i].set(guBand.left, top, guBand.right, top + TASK_ROW_H);
        }
        return guScroll;
    }

    float scrollEditor(float scroll) {
        edScroll = clamp(scroll, 0f, maxEditorScroll());
        for (int i = 0; i < edRowCount; i++) {
            float top = edBand.top + i * TASK_ROW_PITCH - edScroll;
            edRow[i].set(edBand.left, top, edBand.right, top + TASK_ROW_H);
        }
        return edScroll;
    }

    /**
     * The minute bubbles, laid out on a fixed grid rather than the old hand-placed
     * coordinate arrays. Bubbles grow with the value, as in the mockup, but every one is
     * given the same tappable rectangle so the drawn size and the touch size cannot drift
     * apart the way they had.
     */
    private void layoutMinuteBubbles(float left, float top, float right, float bottom) {
        float cellW = (right - left) / 4f;
        float cellH = (bottom - top) / 2f;
        float size = Math.min(cellW, cellH) * 0.96f;
        for (int i = 0; i < 7; i++) {
            int row = i < 4 ? 0 : 1;
            int col = i < 4 ? i : i - 4;
            // Second row has three bubbles; centre them under the four above.
            float rowLeft = left + (row == 0 ? 0f : cellW * 0.5f);
            float cx = rowLeft + col * cellW + cellW / 2f;
            float cy = top + row * cellH + cellH / 2f;
            minuteBubble[i].set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);
        }
    }

    private static void touch(int... unused) { /* keeps band handles referenced */ }

    // ------------------------------------------------------------------- adventure

    private void measureAdventure() {
        begin();
        int bTop = fixed(176f);
        int g1 = gap();
        int bClock = band(170f, 200f, 0.4f, 1f);
        int g2 = gap();
        int bRibbon = band(110f, 140f, 0.3f, 1f);
        int bScene = band(520f, 1400f, 6f, 3f);
        int g3 = gap();
        int bTaskCard = band(150f, 180f, 0.3f, 1f);
        int g4 = gap();
        int bAction = band(150f, 180f, 0.3f, 0f);
        spillBand = bScene;
        solve(play.top, play.height());

        advBack.set(36f, bandTop[bTop] + 13f, 36f + CHIP, bandTop[bTop] + 13f + CHIP);
        advPause.set(W - 36f - CHIP, advBack.top, W - 36f, advBack.bottom);
        advTitle.set(advBack.right, bandTop[bTop], advPause.left, bandBottom(bTop));

        place(bClock, advClock, 250f, W - 250f);
        place(bRibbon, advRibbon, 60f, W - 60f);
        place(bScene, advScene, 0f, W);

        // The goal sits at the right end of the lane the buddy walks along, so that
        // travelling along the trail visibly approaches it.
        float goalSize = Math.min(300f, advScene.height() * 0.42f);
        advGoal.set(W - 60f - goalSize, advScene.bottom - 150f - goalSize,
                    W - 60f, advScene.bottom - 150f);
        advTrail.set(150f, advScene.bottom - 210f, advGoal.left - 40f, advScene.bottom - 90f);
        advProgress.set(100f, advScene.bottom - 62f, W - 100f, advScene.bottom - 34f);

        place(bTaskCard, advTaskCard, 45f, W - 45f);
        place(bAction, advAction, 85f, W - 85f);
        touch(g1, g2, g3, g4);
    }

    // ---------------------------------------------------------------------- finish

    private void measureComplete() {
        begin();
        int bTitle = band(200f, 260f, 1f, 1f);
        int bStage = band(480f, 1000f, 5f, 3f);
        int g1 = gap();
        int bCard = band(280f, 340f, 1f, 1f);
        int g2 = gap();
        int bPlay = band(150f, 175f, 0.3f, 0f);
        int g3 = band(14f, 26f, 0.3f, 1f);
        int bHome = band(140f, 165f, 0.3f, 0f);
        int g4 = gap();
        spillBand = bStage;
        solve(play.top, play.height());

        place(bTitle, cmpTitle, 0f, W);
        place(bStage, cmpStage, 0f, W);
        place(bCard, cmpCard, 90f, W - 90f);
        place(bPlay, cmpPlayAgain, 110f, W - 110f);
        place(bHome, cmpBackHome, 110f, W - 110f);
        touch(g1, g2, g3, g4);
    }

    // ---------------------------------------------------------------- buddy picker

    private void measureBuddyPicker() {
        float top = Math.max(play.top + 60f, play.top);
        pickSheet.set(38f, top, W - 38f, play.bottom);

        begin();
        int bTitle = fixed(190f);
        int bGrid = band(600f, 1800f, 6f, 3f);
        int g1 = gap();
        int bConfirm = band(150f, 175f, 0.3f, 0f);
        int g2 = gap();
        spillBand = bGrid;
        solve(pickSheet.top + 20f, pickSheet.height() - 20f);

        place(bTitle, pickTitle, pickSheet.left, pickSheet.right);
        pickClose.set(pickSheet.right - 30f - CHIP, pickTitle.top + 18f,
                      pickSheet.right - 30f, pickTitle.top + 18f + CHIP);
        place(bGrid, pickGrid, pickSheet.left + 34f, pickSheet.right - 34f);
        place(bConfirm, pickConfirm, pickSheet.left + 90f, pickSheet.right - 90f);

        // Two columns, four rows: the v0.6 target's grid. The older three-column sheet
        // produced cards too small to tap comfortably on a tall phone.
        int cols = 2;
        int rows = (BuddyTheme.COUNT + cols - 1) / cols;
        float cellW = pickGrid.width() / cols;
        float cellH = pickGrid.height() / rows;
        float inset = Math.min(cellW, cellH) * 0.05f;
        for (int i = 0; i < BuddyTheme.COUNT; i++) {
            int row = i / cols, col = i % cols;
            buddyCard[i].set(pickGrid.left + col * cellW + inset,
                             pickGrid.top + row * cellH + inset,
                             pickGrid.left + (col + 1) * cellW - inset,
                             pickGrid.top + (row + 1) * cellH - inset);
        }
        touch(g1, g2);
    }

    // ----------------------------------------------------------------- time picker

    private void measureTimePicker() {
        timeSheet.set(55f, play.top + 60f, W - 55f, play.bottom);

        begin();
        int bTitle = fixed(180f);
        int bDisplay = band(190f, 240f, 1f, 1f);
        int g1 = gap();
        int bBubbles = band(330f, 460f, 3f, 2f);
        int g2 = gap();
        int bSlider = band(120f, 150f, 0.5f, 1f);
        int g3 = gap();
        int bSet = band(150f, 175f, 0.3f, 0f);
        int g4 = gap();
        spillBand = bBubbles;
        solve(timeSheet.top + 20f, timeSheet.height() - 20f);

        place(bTitle, timeTitle, timeSheet.left, timeSheet.right);
        timeClose.set(timeSheet.right - 30f - CHIP, timeTitle.top + 18f,
                      timeSheet.right - 30f, timeTitle.top + 18f + CHIP);

        // Size the steppers from the band height first, then inset the display to clear
        // them, rather than hardcoding a gutter that a taller stepper would grow into.
        place(bDisplay, timeDisplay, timeSheet.left, timeSheet.right);
        float stepper = Math.max(CHIP, Math.min(170f, timeDisplay.height() * 0.8f));
        float gutter = 36f + stepper + 28f;
        timeDisplay.inset(gutter, 0f);
        timeMinus.set(timeSheet.left + 36f, timeDisplay.centerY() - stepper / 2f,
                      timeSheet.left + 36f + stepper, timeDisplay.centerY() + stepper / 2f);
        timePlus.set(timeSheet.right - 36f - stepper, timeMinus.top,
                     timeSheet.right - 36f, timeMinus.bottom);

        RectF bubbles = new RectF();
        place(bBubbles, bubbles, timeSheet.left + 40f, timeSheet.right - 40f);
        layoutPresetBubbles(bubbles);

        place(bSlider, timeSlider, timeSheet.left + 80f, timeSheet.right - 80f);
        place(bSet, timeSet, timeSheet.left + 90f, timeSheet.right - 90f);
        touch(g1, g2, g3, g4);
    }

    private void layoutPresetBubbles(RectF area) {
        float cellW = area.width() / 4f;
        float cellH = area.height() / 2f;
        float size = Math.min(cellW, cellH) * 0.92f;
        for (int i = 0; i < 7; i++) {
            int row = i < 4 ? 0 : 1;
            int col = i < 4 ? i : i - 4;
            float rowLeft = area.left + (row == 0 ? 0f : cellW * 0.5f);
            float cx = rowLeft + col * cellW + cellW / 2f;
            float cy = area.top + row * cellH + cellH / 2f;
            presetBubble[i].set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);
        }
    }

    // ------------------------------------------------------------------ grown-ups

    private void measureGrownUps() {
        guContentHeight = Math.max(0f, GROWN_UP_ROWS * TASK_ROW_PITCH - TASK_ROW_GAP);
        begin();
        int bHeader = fixed(180f);
        int g1 = gap();
        int bRows = band(Math.min(guContentHeight, TASK_ROW_PITCH * 3.2f),
                         Math.max(TASK_ROW_PITCH * 3.2f, guContentHeight), 3f, 4f);
        int g2 = gap();
        int bPanel = band(240f, 360f, 1f, 3f);
        int g3 = gap();
        spillBand = bRows;
        solve(play.top, play.height());

        guBack.set(40f, bandTop[bHeader] + 14f, 40f + CHIP, bandTop[bHeader] + 14f + CHIP);
        guTitle.set(guBack.right, bandTop[bHeader], W - guBack.right, bandBottom(bHeader));

        place(bRows, guBand, 48f, W - 48f);
        scrollGrownUps(guScroll);

        place(bPanel, guPanel, 90f, W - 90f);
        float unlock = Math.min(guPanel.height() * 0.52f, 190f);
        guUnlock.set(guPanel.centerX() - unlock / 2f, guPanel.top + 34f,
                     guPanel.centerX() + unlock / 2f, guPanel.top + 34f + unlock);
        touch(g1, g2, g3);
    }

    // --------------------------------------------------------------- routine editor

    private void measureRoutineEditor(int taskCount) {
        edRowCount = Math.max(0, Math.min(taskCount, MAX_TASK_ROWS));

        begin();
        int bHeader = fixed(180f);
        int g1 = gap();
        int bRows = band(3f * TASK_ROW_PITCH, MAX_TASK_ROWS * TASK_ROW_PITCH, 5f, 3f);
        int g2 = gap();
        int bAdd = band(140f, 165f, 0.3f, 1f);
        int g3 = gap();
        int bSave = band(150f, 175f, 0.3f, 0f);
        int g4 = gap();
        spillBand = bRows;
        solve(play.top, play.height());

        edBack.set(40f, bandTop[bHeader] + 14f, 40f + CHIP, bandTop[bHeader] + 14f + CHIP);
        edTitle.set(edBack.right, bandTop[bHeader], W - edBack.right, bandBottom(bHeader));

        place(bRows, edBand, 48f, W - 48f);
        edContentHeight = Math.max(0f, edRowCount * TASK_ROW_PITCH - TASK_ROW_GAP);
        scrollEditor(edScroll);
        place(bAdd, edAdd, 140f, W - 140f);
        place(bSave, edSave, 90f, W - 90f);
        touch(g1, g2, g3, g4);
    }
}
