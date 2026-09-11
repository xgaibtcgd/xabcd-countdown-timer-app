package com.morningmission.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * The countdown: the buddy travels through its world collecting things on the way to a
 * goal, while the child works through the routine.
 */
final class ScreenAdventure extends Screen {

    private static final int R_BACK = 1, R_PAUSE = 2, R_ACTION = 3;

    private final RectF scratch = new RectF();

    ScreenAdventure(MorningView view) {
        super(view);
    }

    @Override void layout(Layout layout, HitMap hits) {
        hits.addPadded(R_BACK, layout.advBack, layout.minTouchUnits(), 0);
        hits.addPadded(R_PAUSE, layout.advPause, layout.minTouchUnits(), 0);
        hits.add(R_ACTION, layout.advAction);
    }

    @Override void draw(Canvas c, Layout layout, float t, float dt) {
        BuddyTheme theme = view.buddy();
        Engine engine = view.engine;

        view.scene.drawBackground(c, theme, t);
        drawGoal(c, layout, theme, engine);
        drawBuddy(c, layout, theme, engine, t);
        view.scene.drawForeground(c, theme, t, true);

        drawTopBar(c, layout, theme);
        drawClock(c, layout, theme, engine);
        drawRibbon(c, layout, theme, engine, t);
        drawProgress(c, layout, theme, engine);
        drawTaskCard(c, layout, theme, engine);
        drawAction(c, layout, engine);

        view.particles.draw(c);

        if (engine.isPaused()) drawPausedVeil(c, layout);
    }

    /**
     * The buddy walks the length of the trail as the morning passes.
     *
     * <p>The old build parked it at a fixed x while the goal sat at the far right, so it
     * never actually approached the thing it was supposed to be travelling toward --
     * despite the README describing exactly that.
     */
    private void drawBuddy(Canvas c, Layout layout, BuddyTheme theme, Engine engine, float t) {
        RectF trail = layout.advTrail;
        float progress = engine.progress();
        float x = trail.left + trail.width() * progress
                + (float) Math.sin(t * 1.5f) * layout.advScene.width() * 0.016f;
        float feet = trail.centerY();
        float height = Math.min(layout.advScene.height() * 0.46f, Layout.W * 0.42f);
        view.drawBuddy(c, theme.index, x, feet, height, true);

        // A reaction the moment a collectible is reached.
        if (!engine.allDone() && engine.collectedCount() > 0
            && engine.collectibleFraction() < 0.14f) {
            float bubbleW = Layout.W * 0.24f;
            float bubbleH = bubbleW * 0.42f;
            scratch.set(x + height * 0.22f, feet - height - bubbleH * 0.4f,
                        x + height * 0.22f + bubbleW, feet - height + bubbleH * 0.6f);
            if (scratch.right > Layout.W - 20f) scratch.offset(Layout.W - 20f - scratch.right, 0f);
            Theme.card(c, scratch, scratch.height() * 0.42f, 0xF7FFFFFF);
            Theme.fitText(c, theme.munchWord, scratch, Theme.T2, 14f,
                          theme.ink, Paint.Align.CENTER, true);
        }
    }

    private void drawGoal(Canvas c, Layout layout, BuddyTheme theme, Engine engine) {
        RectF box = layout.advGoal;
        float size = Math.min(box.width(), box.height());
        boolean reached = engine.allDone();
        Icons.goal(c, theme.index, box.centerX(), box.centerY(), size,
                   reached ? 1f : 0f, reached ? 1f : 0f);
        if (!reached) {
            // Over the goal, not beneath it: below, it collided with the progress bar.
            Icons.goalLocked(c, box.centerX(), box.centerY() + size * 0.08f, size);
        }
    }

    private void drawTopBar(Canvas c, Layout layout, BuddyTheme theme) {
        Icons.glyphChip(c, Art.GLYPH_CHEVRON_LEFT, layout.advBack, 0xEAFFFFFF,
                        theme.ink, view.pressOn(R_BACK));
        Icons.glyphChip(c, view.engine.isPaused() ? Art.GLYPH_PLAY : Art.GLYPH_PAUSE,
                        layout.advPause, 0xEAFFFFFF, theme.ink, view.pressOn(R_PAUSE));

        RectF title = layout.advTitle;
        float size = Math.min(title.height() * 0.44f, Layout.W * 0.062f);
        // Not white: wordmark() haloes in white, so a white fill vanished into its own
        // outline. The deep blue is Home's "Morning", which ties the two titles together.
        Theme.wordmark(c, "Buddy", title.centerX(), title.top + title.height() * 0.32f,
                       size, 0xFF1857A5);
        Theme.wordmark(c, "Adventure!", title.centerX(), title.top + title.height() * 0.78f,
                       size * 1.06f, 0xFFFFF06A);
    }

    private void drawClock(Canvas c, Layout layout, BuddyTheme theme, Engine engine) {
        RectF box = layout.advClock;
        Theme.card(c, box, box.height() * 0.34f, 0xF8FFFFFF);
        Theme.drawTime(c, engine.remainingMs(), box.centerX(),
                       box.centerY() - box.height() * 0.11f,
                       Math.min(Theme.D1, box.height() * 0.52f), Theme.INK, Paint.Align.CENTER);

        String caption;
        int colour;
        if (engine.isPaused()) {
            caption = "Paused";
            colour = theme.accent;
        } else if (engine.isTimeUp()) {
            caption = "Time is up. Finish your tasks!";
            colour = 0xFFD4552F;
        } else if (engine.isLowTime()) {
            caption = "Nearly there. Keep going!";
            colour = Theme.WARN;
        } else {
            caption = "Keep going!";
            colour = Theme.INK_MUTED;
        }
        Theme.textCentered(c, caption, box.centerX(), box.bottom - box.height() * 0.19f,
                           Theme.B2, colour, Paint.Align.CENTER, true);
    }

    /**
     * The collectibles, floating on the scene rather than inside a white pill.
     *
     * <p>Wraps to two rows past eight items, so a long morning does not shrink them to
     * the point of being unreadable.
     */
    private void drawRibbon(Canvas c, Layout layout, BuddyTheme theme, Engine engine, float t) {
        int total = engine.collectibleCount();
        int collected = engine.collectedCount();
        RectF band = layout.advRibbon;
        int perRow = total > 8 ? (total + 1) / 2 : total;
        int rows = total > 8 ? 2 : 1;
        float rowHeight = band.height() / rows;
        float cell = Math.min(band.width() / perRow, rowHeight);
        float size = cell * 0.82f;

        for (int i = 0; i < total; i++) {
            int row = i / perRow;
            int col = i % perRow;
            int inRow = Math.min(perRow, total - row * perRow);
            float rowWidth = inRow * cell;
            float x = band.centerX() - rowWidth * 0.5f + col * cell + cell * 0.5f;
            float y = band.top + row * rowHeight + rowHeight * 0.5f;
            boolean got = i < collected;
            // The newest one pops as it is picked up.
            float pop = (got && i == collected - 1)
                      ? Math.max(0f, 1f - engine.collectibleFraction() * 6f) : 0f;
            Icons.collectible(c, theme.index, x, y, size, got, pop);
        }
    }

    private void drawProgress(Canvas c, Layout layout, BuddyTheme theme, Engine engine) {
        RectF track = layout.advProgress;
        Paint fill = Theme.FILL;
        fill.setShader(null);
        float radius = track.height() * 0.5f;
        fill.setColor(0x59FFFFFF);
        c.drawRoundRect(track, radius, radius, fill);

        float fraction = engine.collectibleCount() == 0 ? 0f
                       : engine.collectedCount() / (float) engine.collectibleCount();
        if (fraction > 0f) {
            scratch.set(track.left, track.top,
                        track.left + Math.max(track.height(), track.width() * fraction),
                        track.bottom);
            fill.setColor(theme.primary);
            c.drawRoundRect(scratch, radius, radius, fill);
        }

        String label = engine.collectedCount() + " of " + engine.collectibleCount() + " "
                     + theme.collectibleNoun(engine.collectibleCount());
        Theme.textCentered(c, label, track.left, track.top - track.height() * 1.2f,
                           Theme.B2, 0xFFFFFFFF, Paint.Align.LEFT, true);
    }

    private void drawTaskCard(Canvas c, Layout layout, BuddyTheme theme, Engine engine) {
        RectF box = layout.advTaskCard;
        Theme.card(c, box, box.height() * 0.26f, 0xF8FFFFFF);
        if (engine.taskCount() == 0) {
            Theme.fitText(c, "Add some tasks in Grown-Ups", box, Theme.T2, 16f,
                          Theme.INK_MUTED, Paint.Align.CENTER, true);
            return;
        }

        float pad = box.height() * 0.16f;
        float iconSize = box.height() - pad * 2f;
        int index = Math.min(engine.activeIndex(), engine.taskCount() - 1);
        Icons.activityChip(c, Art.activityKind(engine.taskKey(index)), theme,
                           box.left + pad + iconSize * 0.5f, box.centerY(), iconSize, false);

        float textLeft = box.left + pad * 2f + iconSize;
        float counterWidth = Layout.W * 0.11f;
        scratch.set(textLeft, box.top + pad * 0.5f,
                    box.right - pad - counterWidth, box.centerY() + box.height() * 0.04f);
        Theme.fitText(c, engine.taskName(index), scratch, Theme.T1, 18f,
                      Theme.INK, Paint.Align.LEFT, true);
        scratch.set(textLeft, box.centerY() + box.height() * 0.06f,
                    box.right - pad - counterWidth, box.bottom - pad * 0.5f);
        Theme.fitText(c, Art.ACTIVITY_SUBTITLES[Art.activityKind(engine.taskKey(index))],
                      scratch, Theme.B2, 14f, Theme.INK_MUTED, Paint.Align.LEFT, false);

        Theme.textCentered(c, (index + 1) + " of " + engine.taskCount(),
                           box.right - pad, box.centerY(), Theme.B1,
                           theme.ink, Paint.Align.RIGHT, true);
    }

    private void drawAction(Canvas c, Layout layout, Engine engine) {
        RectF box = layout.advAction;
        float press = view.pressOn(R_ACTION);
        boolean done = engine.allDone();
        Theme.button(c, box, box.height() * 0.5f,
                     done ? Theme.SUCCESS_DEEP : Theme.SUCCESS,
                     Theme.darken(done ? Theme.SUCCESS_DEEP : Theme.SUCCESS, 0.22f),
                     press, 1f);
        float cy = box.centerY() + box.height() * 0.04f * press;
        String label = done ? "YOU DID IT!" : "I DID IT!";
        float glyph = box.height() * 0.40f;
        float room = box.width() - glyph - 20f - box.height() * 0.5f;
        float textSize = Theme.labelSize(label, Theme.H2, 16f, room);
        float textWidth = Theme.measureLabel(label, textSize);
        float startX = box.centerX() - (glyph + 20f + textWidth) * 0.5f;
        Icons.glyph(c, done ? Art.GLYPH_STAR : Art.GLYPH_CHECK,
                    startX + glyph * 0.5f, cy, glyph, 0xFFFFFFFF);
        Theme.label(c, label, startX + glyph + 20f, cy,
                    textSize, 0xFFFFFFFF, Paint.Align.LEFT);
    }

    private void drawPausedVeil(Canvas c, Layout layout) {
        Paint fill = Theme.FILL;
        fill.setShader(null);
        fill.setColor(0x7A0E3560);
        c.drawRect(layout.advScene, fill);
        Theme.label(c, "Tap play to carry on", layout.advScene.centerX(),
                    layout.advScene.centerY(), Theme.H2, 0xFFFFFFFF,
                    Paint.Align.CENTER);
    }

    @Override void onRegion(int id, int data) {
        switch (id) {
            case R_BACK:
                // Leaving the adventure is a parent action: it is the same gate as
                // unlocking kid mode, never a plain back.
                view.activity.requestParentUnlock();
                break;
            case R_PAUSE:
                if (view.engine.isPaused()) view.engine.resume();
                else view.engine.pause();
                view.startClock();
                break;
            case R_ACTION:
                if (!view.engine.allDone()) {
                    view.engine.completeActive();
                } else {
                    view.route(MorningView.SCREEN_COMPLETE);
                }
                break;
            default:
                break;
        }
    }

    @Override boolean onBack() {
        view.activity.requestParentUnlock();
        return true;
    }
}
