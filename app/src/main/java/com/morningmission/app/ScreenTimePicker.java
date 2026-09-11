package com.morningmission.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * Choosing how long the morning is.
 *
 * <p>The slider now works. The old handler only looked at ACTION_UP, so dragging it did
 * nothing: it could only be tapped at a point. Steppers are new, for nudging a minute at
 * a time without hunting along a slider.
 */
final class ScreenTimePicker extends Screen {

    private static final int R_CLOSE = 1, R_PRESET = 2, R_MINUS = 3, R_PLUS = 4,
                             R_SLIDER = 5, R_SET = 6;

    private static final int[] PRESETS = {1, 2, 5, 10, 15, 30, 60};
    private static final int MIN_MINUTES = 1;
    private static final int MAX_MINUTES = 120;

    private final RectF scratch = new RectF();
    /** Reused for the presets' idle float; see Anim.drift. */
    private final Anim.Transform drift = new Anim.Transform();
    private int pending = 15;

    ScreenTimePicker(MorningView view) {
        super(view);
    }

    @Override void onEnter() {
        pending = view.minutes();
    }

    @Override void layout(Layout layout, HitMap hits) {
        hits.addPadded(R_CLOSE, layout.timeClose, layout.minTouchUnits(), 0);
        for (int i = 0; i < PRESETS.length; i++) {
            hits.addPadded(R_PRESET, layout.presetBubble[i], layout.minTouchUnits(), i);
        }
        hits.add(R_MINUS, layout.timeMinus);
        hits.add(R_PLUS, layout.timePlus);
        // The slider's touch region is taller than the drawn track, so the knob can be
        // grabbed without hitting a two-unit-high bar exactly.
        scratch.set(layout.timeSlider.left - 30f, layout.timeSlider.top - 46f,
                    layout.timeSlider.right + 30f, layout.timeSlider.bottom + 46f);
        hits.add(R_SLIDER, scratch);
        hits.add(R_SET, layout.timeSet);
    }

    @Override void draw(Canvas c, Layout layout, float t, float dt) {
        BuddyTheme theme = view.buddy();
        view.scene.drawBackground(c, theme, t);
        view.scene.drawForeground(c, theme, t, false);

        Paint fill = Theme.FILL;
        fill.setShader(null);
        fill.setColor(Theme.SCRIM);
        c.drawRect(0f, 0f, Layout.W, layout.height, fill);

        Theme.card(c, layout.timeSheet, layout.timeSheet.width() * 0.05f, 0xFFF7FCFF);

        RectF title = layout.timeTitle;
        Theme.wordmark(c, "Customise Time", title.centerX(),
                       title.top + title.height() * 0.44f,
                       Math.min(title.height() * 0.36f, Layout.W * 0.055f), 0xFF2473D4);
        Theme.textCentered(c, "Pick a bubble, nudge it, or slide to any time",
                           title.centerX(), title.bottom - title.height() * 0.20f,
                           Theme.B1, Theme.INK_MUTED, Paint.Align.CENTER, false);
        Icons.glyphChip(c, Art.GLYPH_CLOSE, layout.timeClose, 0xFFEDF3FA,
                        Theme.INK_MUTED, view.pressOn(R_CLOSE));

        Theme.card(c, layout.timeDisplay, layout.timeDisplay.height() * 0.30f, 0xFFFFFFFF);
        Theme.drawTime(c, pending * 60_000L, layout.timeDisplay.centerX(),
                       layout.timeDisplay.centerY(),
                       Math.min(Theme.D1, layout.timeDisplay.height() * 0.50f),
                       Theme.INK, Paint.Align.CENTER);

        stepper(c, layout.timeMinus, Art.GLYPH_MINUS, theme, view.pressOn(R_MINUS),
                pending > MIN_MINUTES);
        stepper(c, layout.timePlus, Art.GLYPH_PLUS, theme, view.pressOn(R_PLUS),
                pending < MAX_MINUTES);

        drawPresets(c, layout, theme, t);
        drawSlider(c, layout, theme);

        RectF set = layout.timeSet;
        float press = view.pressOn(R_SET);
        Theme.button(c, set, set.height() * 0.5f, theme.primary,
                     Theme.darken(theme.primary, 0.22f), press);
        Theme.label(c, "Set Time", set.centerX(),
                    set.centerY() + set.height() * 0.04f * press,
                    Theme.labelSize("Set Time", Theme.buttonLabelSize(set), 18f,
                                    Theme.labelRoom(set, 0f)),
                    0xFFFFFFFF, Paint.Align.CENTER);
    }

    private void stepper(Canvas c, RectF box, int glyph, BuddyTheme theme,
                         float press, boolean enabled) {
        Icons.glyphChip(c, glyph, box, enabled ? 0xFFFFFFFF : 0xFFEFF3F8,
                        enabled ? theme.primary : Theme.INK_FAINT, press);
    }

    private void drawPresets(Canvas c, Layout layout, BuddyTheme theme, float t) {
        Paint fill = Theme.FILL;
        fill.setShader(null);
        for (int i = 0; i < PRESETS.length; i++) {
            RectF box = layout.presetBubble[i];
            boolean on = PRESETS[i] == pending;
            float scale = 0.62f + 0.38f * (i / (float) (PRESETS.length - 1));
            float radius = Math.min(box.width(), box.height()) * 0.5f * scale;
            radius *= 1f - 0.08f * view.pressOn(R_PRESET, i);
            // The same idle float as the minute bubbles on Home; they are one component.
            Anim.drift(i, t, ScreenHome.driftLimit(box, radius), drift);
            radius *= drift.scaleY;
            float cx = box.centerX() + drift.dx, cy = box.centerY() + drift.dy;
            Clay.contactShadow(c, cx, cy + radius * 0.85f,
                               radius * 0.8f, radius * 0.28f, 0.85f);
            fill.setColor(on ? theme.primary : Theme.mix(theme.light, 0xFFFFFFFF, 0.25f));
            c.drawCircle(cx, cy, radius, fill);
            Theme.glossCircle(c, cx, cy, radius, 1f);
            Theme.label(c, Integer.toString(PRESETS[i]), cx, cy,
                        Math.max(19f, radius * 0.76f),
                        on ? 0xFFFFFFFF : theme.ink, Paint.Align.CENTER);
        }
    }

    private void drawSlider(Canvas c, Layout layout, BuddyTheme theme) {
        RectF box = layout.timeSlider;
        float cy = box.centerY();
        float trackHeight = Math.max(14f, box.height() * 0.18f);
        Paint fill = Theme.FILL;
        fill.setShader(null);

        scratch.set(box.left, cy - trackHeight * 0.5f, box.right, cy + trackHeight * 0.5f);
        fill.setColor(0xFFD7E4F3);
        c.drawRoundRect(scratch, trackHeight * 0.5f, trackHeight * 0.5f, fill);

        float fraction = (pending - MIN_MINUTES) / (float) (MAX_MINUTES - MIN_MINUTES);
        float knobX = box.left + box.width() * fraction;
        scratch.set(box.left, cy - trackHeight * 0.5f, knobX, cy + trackHeight * 0.5f);
        fill.setColor(theme.primary);
        c.drawRoundRect(scratch, trackHeight * 0.5f, trackHeight * 0.5f, fill);

        float knob = box.height() * 0.42f;
        Clay.contactShadow(c, knobX, cy + knob * 0.8f, knob * 0.8f, knob * 0.3f, 1f);
        fill.setColor(0xFFFFFFFF);
        c.drawCircle(knobX, cy, knob, fill);
        fill.setColor(theme.primary);
        c.drawCircle(knobX, cy, knob * 0.42f, fill);

        Theme.textCentered(c, Integer.toString(MIN_MINUTES), box.left,
                           box.bottom + box.height() * 0.16f, Theme.C1,
                           Theme.INK_MUTED, Paint.Align.CENTER, true);
        Theme.textCentered(c, Integer.toString(MAX_MINUTES), box.right,
                           box.bottom + box.height() * 0.16f, Theme.C1,
                           Theme.INK_MUTED, Paint.Align.CENTER, true);
    }

    @Override boolean onPressDown(int id, int data, float x, float y) {
        if (id != R_SLIDER) return false;
        setFromSlider(x);
        return true;
    }

    @Override void onDrag(float x, float y) {
        setFromSlider(x);
    }

    private void setFromSlider(float x) {
        RectF box = view.layout.timeSlider;
        float fraction = (x - box.left) / Math.max(1f, box.width());
        fraction = Theme.clamp(fraction, 0f, 1f);
        pending = MIN_MINUTES + Math.round(fraction * (MAX_MINUTES - MIN_MINUTES));
    }

    @Override void onRegion(int id, int data) {
        switch (id) {
            case R_PRESET:
                pending = PRESETS[data];
                break;
            case R_MINUS:
                pending = Math.max(MIN_MINUTES, pending - 1);
                break;
            case R_PLUS:
                pending = Math.min(MAX_MINUTES, pending + 1);
                break;
            case R_SET:
                view.setMinutes(pending);
                view.resetRoutine();
                view.route(MorningView.SCREEN_HOME);
                break;
            case R_CLOSE:
                view.route(MorningView.SCREEN_HOME);
                break;
            default:
                break;
        }
    }

    @Override boolean onBack() {
        view.route(MorningView.SCREEN_HOME);
        return true;
    }
}
