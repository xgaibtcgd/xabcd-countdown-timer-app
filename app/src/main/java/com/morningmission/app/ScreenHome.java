package com.morningmission.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * The setup screen: pick a buddy and a length, look over the routine, start the morning.
 */
final class ScreenHome extends Screen {

    private static final int R_GEAR = 1, R_GROWN_UPS = 2, R_BUDDY = 3, R_MINUTE = 4,
                             R_TIMER = 5, R_EDIT = 6, R_TASK = 7, R_START = 8, R_NAV = 9;

    private static final int[] MINUTES = {1, 2, 5, 10, 15, 30, 60};
    /** The bubble colours from the mockup, in the same order as the values. */
    private static final int[] BUBBLE = {
        0xFFEAF5FF, 0xFFD9F8F2, 0xFFFFF0A9, 0xFFE8DDFF, 0xFFFF8657, 0xFFB7D9FF, 0xFF93ECC1
    };
    /** "Mission" is set letter by letter, the way the logo is drawn. */
    private static final int[] LOGO = {
        0xFFE8533F, 0xFFF2A02C, 0xFFF6C844, 0xFF3FA96A, 0xFF2879ED, 0xFF7B5BD6, 0xFFEC4777
    };
    private static final String[] NAV_LABELS = {"Today", "Rewards", "Routine", "Grown-Ups"};
    private static final int[] NAV_GLYPHS = {
        Art.GLYPH_HOME, Art.GLYPH_STAR, Art.GLYPH_LIST, Art.GLYPH_PEOPLE
    };

    private final RectF scratch = new RectF();
    private float taskScroll;

    ScreenHome(MorningView view) {
        super(view);
    }

    @Override void layout(Layout layout, HitMap hits) {
        layout.scrollTasks(taskScroll);
        hits.addPadded(R_GEAR, layout.gearChip, layout.minTouchUnits(), 0);
        hits.addPadded(R_GROWN_UPS, layout.grownUpsChip, layout.minTouchUnits(), 0);
        hits.add(R_BUDDY, layout.buddySlot);
        for (int i = 0; i < MINUTES.length; i++) {
            hits.addPadded(R_MINUTE, layout.minuteBubble[i], layout.minTouchUnits(), i);
        }
        hits.add(R_TIMER, layout.timerCard);
        hits.addPadded(R_EDIT, layout.editChip, layout.minTouchUnits(), 0);
        for (int i = 0; i < layout.taskRowCount; i++) {
            hits.addClipped(R_TASK, layout.taskRow[i], layout.taskBand, i);
        }
        hits.add(R_START, layout.startBtn);
        for (int i = 0; i < Layout.NAV_ITEMS; i++) {
            hits.add(R_NAV, layout.navItem[i], i);
        }
    }

    @Override boolean animating() { return true; }

    @Override void draw(Canvas c, Layout layout, float t, float dt) {
        BuddyTheme theme = view.buddy();
        view.scene.drawBackground(c, theme, t);

        drawWordmark(c, layout);
        view.drawBuddy(c, theme.index, layout.buddySlot.centerX(),
                       layout.buddySlot.bottom - layout.buddySlot.height() * 0.06f,
                       layout.buddySlot.height() * 0.82f, true);
        drawMinuteBubbles(c, layout, theme);

        view.scene.drawForeground(c, theme, t, true);

        drawHeader(c, layout, theme);
        drawTimerCard(c, layout, theme);
        drawRoutineHeader(c, layout, theme);
        drawTasks(c, layout, theme);
        drawStart(c, layout);
        drawNav(c, layout, theme);
    }

    private void drawWordmark(Canvas c, Layout layout) {
        RectF box = layout.wordmark;
        float size = Math.min(box.height() * 0.46f, Layout.W * 0.13f);
        Theme.wordmark(c, "Morning", box.centerX(), box.top + box.height() * 0.30f,
                       size, 0xFF1857A5);
        Theme.wordmarkLetters(c, "Mission", box.centerX(), box.top + box.height() * 0.74f,
                              size * 1.05f, LOGO);
        // The swoosh under the logo.
        Paint stroke = Theme.STROKE;
        stroke.setShader(null);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(size * 0.11f);
        stroke.setColor(0xFFFFC94A);
        float w = size * 2.6f;
        scratch.set(box.centerX() - w, box.bottom - size * 0.55f,
                    box.centerX() + w, box.bottom + size * 0.30f);
        c.drawArc(scratch, 20f, 140f, false, stroke);
    }

    private void drawHeader(Canvas c, Layout layout, BuddyTheme theme) {
        Icons.glyphChip(c, Art.GLYPH_GEAR, layout.gearChip, 0xF7FFFFFF, theme.ink,
                        view.pressOn(R_GEAR));

        RectF chip = layout.grownUpsChip;
        float press = view.pressOn(R_GROWN_UPS);
        scratch.set(chip);
        scratch.offset(0f, chip.height() * 0.06f * press);
        Theme.card(c, scratch, scratch.height() * 0.5f, 0xF7FFFFFF);
        float glyphSize = scratch.height() * 0.46f;
        float textWidth = Theme.measure("Grown-Ups", Theme.B1, true);
        float startX = scratch.centerX() - (glyphSize + 12f + textWidth) * 0.5f;
        Icons.glyph(c, Art.GLYPH_PEOPLE, startX + glyphSize * 0.5f, scratch.centerY(),
                    glyphSize, theme.ink);
        Theme.textCentered(c, "Grown-Ups", startX + glyphSize + 12f, scratch.centerY(),
                           Theme.B1, theme.ink, Paint.Align.LEFT, true);
    }

    private void drawMinuteBubbles(Canvas c, Layout layout, BuddyTheme theme) {
        int selected = view.minutes();
        Theme.textCentered(c, "MINUTES", layout.minutesLabel.centerX(),
                           layout.minutesLabel.centerY(), Theme.C1, 0xFF5C7086,
                           Paint.Align.CENTER, true);
        for (int i = 0; i < MINUTES.length; i++) {
            RectF box = layout.minuteBubble[i];
            boolean on = MINUTES[i] == selected;
            // Bubbles grow with their value, as in the mockup, but every one keeps the
            // same tap target: the drawn size and the touch size are no longer separate
            // numbers that can drift.
            float scale = 0.60f + 0.40f * (i / (float) (MINUTES.length - 1));
            float radius = Math.min(box.width(), box.height()) * 0.5f * scale;
            radius *= 1f - 0.08f * view.pressOn(R_MINUTE, i);
            float cx = box.centerX(), cy = box.centerY();

            Clay.contactShadow(c, cx, cy + radius * 0.85f, radius * 0.8f, radius * 0.28f, 0.9f);
            Paint fill = Theme.FILL;
            fill.setShader(null);
            fill.setColor(on ? theme.primary : BUBBLE[i]);
            c.drawCircle(cx, cy, radius, fill);
            if (on) {
                Paint stroke = Theme.STROKE;
                stroke.setShader(null);
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(radius * 0.16f);
                stroke.setColor(0xFFFFFFFF);
                c.drawCircle(cx, cy, radius * 1.06f, stroke);
            }
            Theme.textCentered(c, Integer.toString(MINUTES[i]), cx, cy,
                               Math.max(20f, radius * 0.82f),
                               on ? 0xFFFFFFFF : 0xFF173C79, Paint.Align.CENTER, true);
        }
    }

    private void drawTimerCard(Canvas c, Layout layout, BuddyTheme theme) {
        RectF box = layout.timerCard;
        scratch.set(box);
        scratch.offset(0f, box.height() * 0.04f * view.pressOn(R_TIMER));
        Theme.card(c, scratch, scratch.height() * 0.30f, 0xF9FFFFFF);
        boolean running = view.engine.isRunning() && !view.engine.allDone();
        Theme.drawTime(c, running ? view.engine.remainingMs() : view.minutes() * 60_000L,
                       scratch.centerX(), scratch.centerY() - scratch.height() * 0.10f,
                       Math.min(Theme.D1, scratch.height() * 0.50f),
                       running ? Theme.CTA_DEEP : Theme.INK, Paint.Align.CENTER);
        Theme.textCentered(c, running ? "left to get ready" : "Tap to customise",
                           scratch.centerX(), scratch.bottom - scratch.height() * 0.19f,
                           Theme.B2, Theme.INK_MUTED, Paint.Align.CENTER, false);
        Icons.glyphIn(c, Art.GLYPH_PENCIL, layout.timerPencil, 0.62f, theme.accent);
    }

    private void drawRoutineHeader(Canvas c, Layout layout, BuddyTheme theme) {
        Theme.textCentered(c, "Today's Mission", layout.routineHeader.left,
                           layout.routineHeader.centerY(), Theme.H2, Theme.INK,
                           Paint.Align.LEFT, true);
        RectF chip = layout.editChip;
        scratch.set(chip);
        scratch.offset(0f, chip.height() * 0.06f * view.pressOn(R_EDIT));
        Theme.card(c, scratch, scratch.height() * 0.5f, 0xF6FFFFFF);
        float glyph = scratch.height() * 0.44f;
        float textWidth = Theme.measure("Edit", Theme.B1, true);
        float startX = scratch.centerX() - (glyph + 10f + textWidth) * 0.5f;
        Icons.glyph(c, Art.GLYPH_PENCIL, startX + glyph * 0.5f, scratch.centerY(),
                    glyph, theme.ink);
        Theme.textCentered(c, "Edit", startX + glyph + 10f, scratch.centerY(),
                           Theme.B1, theme.ink, Paint.Align.LEFT, true);
    }

    private void drawTasks(Canvas c, Layout layout, BuddyTheme theme) {
        Engine engine = view.engine;
        if (layout.taskRowCount == 0) {
            Theme.textCentered(c, "No tasks yet. Add some in Grown-Ups.",
                               layout.taskBand.centerX(), layout.taskBand.centerY(),
                               Theme.T2, Theme.INK_MUTED, Paint.Align.CENTER, false);
            return;
        }
        c.save();
        c.clipRect(layout.taskBand);
        for (int i = 0; i < layout.taskRowCount; i++) {
            RectF row = layout.taskRow[i];
            if (row.bottom < layout.taskBand.top || row.top > layout.taskBand.bottom) continue;
            boolean done = engine.isTaskDone(i);
            boolean active = i == engine.activeIndex() && !done;
            int face = done ? 0xFFEAF9EA : active ? 0xFFFFF3C9 : 0xF9FFFFFF;

            scratch.set(row);
            scratch.offset(0f, row.height() * 0.04f * view.pressOn(R_TASK, i));
            Theme.card(c, scratch, scratch.height() * 0.28f, face);

            float pad = scratch.height() * 0.14f;
            float iconSize = scratch.height() - pad * 2f;
            Icons.activityChip(c, Art.activityKind(engine.taskKey(i)), theme,
                               scratch.left + pad + iconSize * 0.5f, scratch.centerY(),
                               iconSize, done);

            float textLeft = scratch.left + pad * 2f + iconSize;
            float checkSize = scratch.height() * 0.42f;
            float textRight = scratch.right - pad * 1.4f - checkSize;
            scratch.set(textLeft, scratch.top, textRight, scratch.centerY());
            Theme.fitText(c, engine.taskName(i), scratch, Theme.T1, 18f,
                          done ? 0xFF4A6B57 : Theme.INK, Paint.Align.LEFT, true);
            scratch.set(textLeft, row.centerY() + row.height() * 0.04f,
                        textRight, row.bottom - row.height() * 0.16f);
            Theme.fitText(c, done ? "Done!" : Art.ACTIVITY_SUBTITLES[
                              Art.activityKind(engine.taskKey(i))],
                          scratch, Theme.B2, 14f,
                          done ? Theme.SUCCESS_DEEP : Theme.INK_MUTED,
                          Paint.Align.LEFT, false);

            drawCheckCircle(c, row.right - pad - checkSize * 0.5f, row.centerY(),
                            checkSize, done, active);
        }
        c.restore();

        if (layout.taskRowCount > 0 && layout.maxTaskScroll() > 1f) {
            drawScrollHint(c, layout);
        }
    }

    private void drawCheckCircle(Canvas c, float cx, float cy, float size,
                                 boolean done, boolean active) {
        Paint fill = Theme.FILL;
        Paint stroke = Theme.STROKE;
        fill.setShader(null);
        stroke.setShader(null);
        if (done) {
            fill.setColor(Theme.SUCCESS);
            c.drawCircle(cx, cy, size * 0.5f, fill);
            Icons.glyph(c, Art.GLYPH_CHECK, cx, cy, size * 0.62f, 0xFFFFFFFF);
        } else {
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(size * 0.11f);
            stroke.setColor(active ? Theme.WARN : 0xFFD3DCE8);
            c.drawCircle(cx, cy, size * 0.44f, stroke);
        }
    }

    /** A soft fade at the edges of the list, so a cut-off row reads as more to come. */
    private void drawScrollHint(Canvas c, Layout layout) {
        Paint fill = Theme.FILL;
        fill.setShader(null);
        float fade = layout.taskBand.height() * 0.06f;
        fill.setColor(0x22102A44);
        if (layout.taskRow[0].top < layout.taskBand.top - 1f) {
            c.drawRect(layout.taskBand.left, layout.taskBand.top,
                       layout.taskBand.right, layout.taskBand.top + fade, fill);
        }
        if (layout.taskRow[layout.taskRowCount - 1].bottom > layout.taskBand.bottom + 1f) {
            c.drawRect(layout.taskBand.left, layout.taskBand.bottom - fade,
                       layout.taskBand.right, layout.taskBand.bottom, fill);
        }
    }

    private void drawStart(Canvas c, Layout layout) {
        float press = view.pressOn(R_START);
        RectF box = layout.startBtn;
        // A morning already under way -- a grown-up unlocked and stepped out of it --
        // gets a way back in rather than a button that looks like it would restart.
        boolean resuming = view.engine.isRunning() && !view.engine.allDone();
        String label = resuming ? "BACK TO THE MORNING" : "START MORNING";
        Theme.button(c, box, box.height() * 0.5f, Theme.CTA, Theme.CTA_DEEP, press);
        float cy = box.centerY() + box.height() * 0.04f * press;
        float size = box.height() * 0.40f;
        float textSize = resuming ? Theme.T2 : Theme.H2;
        float textWidth = Theme.measure(label, textSize, true);
        float startX = box.centerX() - (size + 20f + textWidth) * 0.5f;
        Icons.glyph(c, Art.GLYPH_PLAY, startX + size * 0.5f, cy, size, 0xFFFFFFFF);
        Theme.textCentered(c, label, startX + size + 20f, cy,
                           textSize, 0xFFFFFFFF, Paint.Align.LEFT, true);
    }

    private void drawNav(Canvas c, Layout layout, BuddyTheme theme) {
        Theme.card(c, layout.navBar, layout.navBar.height() * 0.38f, 0xF7FFFFFF);
        for (int i = 0; i < Layout.NAV_ITEMS; i++) {
            RectF item = layout.navItem[i];
            boolean selected = i == 0;
            int colour = selected ? theme.primary : Theme.INK_FAINT;
            float press = view.pressOn(R_NAV, i);
            float cy = item.centerY() - item.height() * 0.12f + item.height() * 0.05f * press;
            Icons.glyph(c, NAV_GLYPHS[i], item.centerX(), cy, item.height() * 0.34f, colour);
            Theme.textCentered(c, NAV_LABELS[i], item.centerX(),
                               item.bottom - item.height() * 0.22f,
                               Theme.C1, colour, Paint.Align.CENTER, selected);
        }
    }

    @Override boolean onScroll(float dy) {
        if (view.layout.maxTaskScroll() <= 0f) return false;
        taskScroll = view.layout.scrollTasks(taskScroll + dy);
        return true;
    }

    @Override void onRegion(int id, int data) {
        switch (id) {
            case R_GEAR:
            case R_GROWN_UPS:
                view.activity.openGrownUps();
                break;
            case R_BUDDY:
                view.route(MorningView.SCREEN_BUDDY_PICKER);
                break;
            case R_MINUTE:
                if (view.engine.isRunning()) {
                    view.activity.toast("The morning is already under way.");
                    break;
                }
                view.setMinutes(MINUTES[data]);
                view.resetRoutine();
                break;
            case R_TIMER:
                if (view.engine.isRunning()) {
                    view.activity.toast("The morning is already under way.");
                    break;
                }
                view.route(MorningView.SCREEN_TIME_PICKER);
                break;
            case R_EDIT:
                view.activity.openRoutineEditor();
                break;
            case R_TASK:
                // Tapping a row on the setup screen previews its buddy pose rather than
                // completing it: nothing can be ticked off before the morning starts.
                view.activity.playBuddySound(view.buddy().index);
                break;
            case R_START:
                view.startMorning();
                break;
            case R_NAV:
                if (data == 2) view.activity.openRoutineEditor();
                else if (data == 3) view.activity.openGrownUps();
                else if (data == 1) view.activity.toast("Rewards are coming soon!");
                break;
            default:
                break;
        }
    }
}
