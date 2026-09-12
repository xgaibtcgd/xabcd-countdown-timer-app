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

    /** Preset durations in seconds. Eight of them, so the grid is a tidy four by two. */
    private static final int[] PRESETS = {30, 60, 120, 300, 600, 900, 1800, 3600};

    /**
     * Every duration the controls can land on, in seconds.
     *
     * <p>The slider used to map its width onto 1..120 whole minutes, so seconds were
     * simply unreachable. Rather than adding a second control for them, the slider and
     * the steppers now walk this table: fifteen-second steps where a short timer needs
     * the resolution, half-minutes through the middle, whole minutes once a morning is
     * long enough that thirty seconds either way makes no difference. Every value you
     * can land on is one a person would actually choose -- there is no 7:43 -- and the
     * display already reads m:ss, so nothing else had to change to show them.
     */
    private static final int[] STOPS = buildStops();

    private static int[] buildStops() {
        int[] out = new int[512];
        int n = 0;
        for (int s = 15; s < 120; s += 15) out[n++] = s;
        for (int s = 120; s < 600; s += 30) out[n++] = s;
        for (int s = 600; s <= 7200; s += 60) out[n++] = s;
        int[] trimmed = new int[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    /** The stop at or below {@code seconds}, so a stored value always maps onto one. */
    static int stopIndex(int seconds) {
        int lo = 0, hi = STOPS.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (STOPS[mid] <= seconds) lo = mid; else hi = mid - 1;
        }
        return lo;
    }

    static int stopCount() { return STOPS.length; }

    static int stopAt(int index) {
        return STOPS[index < 0 ? 0 : Math.min(index, STOPS.length - 1)];
    }

    /**
     * A colour per preset, cool and light for a short morning through to deep and warm
     * for a long one.
     *
     * <p>They used to share one tint -- the buddy's light shade mixed with white -- so
     * eight bubbles differing only in size read as one shape repeated. The colour now
     * says the same thing the size already says.
     */
    private static final int[] PRESET_COLORS = {
        0xFFD9F8F2, 0xFFCFEEFF, 0xFFC9E4FF, 0xFFDCDBFF,
        0xFFFFF0A9, 0xFFFFD98A, 0xFFFFB067, 0xFFFF8A6B
    };

    private final RectF scratch = new RectF();
    /** Reused for the presets' idle float; see Anim.drift. */
    private final Anim.Transform drift = new Anim.Transform();
    /** The chosen duration in seconds, always one of {@link #STOPS}. */
    private int pending = 900;

    ScreenTimePicker(MorningView view) {
        super(view);
    }

    @Override void onEnter() {
        pending = stopAt(stopIndex(view.durationSeconds()));
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
        Theme.drawTime(c, pending * 1000L, layout.timeDisplay.centerX(),
                       layout.timeDisplay.centerY(),
                       Math.min(Theme.D1, layout.timeDisplay.height() * 0.50f),
                       Theme.INK, Paint.Align.CENTER);

        stepper(c, layout.timeMinus, Art.GLYPH_MINUS, theme, view.pressOn(R_MINUS),
                pending > STOPS[0]);
        stepper(c, layout.timePlus, Art.GLYPH_PLUS, theme, view.pressOn(R_PLUS),
                pending < STOPS[STOPS.length - 1]);

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
            fill.setColor(on ? theme.primary : PRESET_COLORS[i % PRESET_COLORS.length]);
            c.drawCircle(cx, cy, radius, fill);
            Theme.glossCircle(c, cx, cy, radius, 1f);
            if (on) {
                Paint stroke = Theme.STROKE;
                stroke.setShader(null);
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(radius * 0.16f);
                stroke.setColor(0xFFFFFFFF);
                c.drawCircle(cx, cy, radius * 1.06f, stroke);
            }
            // Minutes are bare numbers; the sub-minute preset carries its unit, so "30s"
            // and "30" cannot be read as the same thing.
            String label = PRESETS[i] < 60 ? PRESETS[i] + "s"
                                           : Integer.toString(PRESETS[i] / 60);
            Theme.label(c, label, cx, cy,
                        Math.max(19f, radius * (PRESETS[i] < 60 ? 0.56f : 0.76f)),
                        on ? 0xFFFFFFFF : 0xFF4A3A22, Paint.Align.CENTER);
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

        float fraction = stopIndex(pending) / (float) (STOPS.length - 1);
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

        // Sized from the slider rather than the fixed C1, which was 17 units against a
        // 150-unit control -- and the labels are longer strings now besides.
        float scaleSize = Theme.clamp(box.height() * 0.26f, 20f, 34f);
        Theme.textCentered(c, TimeText.toText(STOPS[0] * 1000L), box.left,
                           box.bottom + box.height() * 0.24f, scaleSize,
                           Theme.INK_MUTED, Paint.Align.CENTER, true);
        Theme.textCentered(c, TimeText.toText(STOPS[STOPS.length - 1] * 1000L), box.right,
                           box.bottom + box.height() * 0.24f, scaleSize,
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
        pending = STOPS[Math.round(fraction * (STOPS.length - 1))];
    }

    @Override void onRegion(int id, int data) {
        switch (id) {
            case R_PRESET:
                pending = PRESETS[data];
                break;
            case R_MINUS:
                pending = stopAt(stopIndex(pending) - 1);
                break;
            case R_PLUS:
                pending = stopAt(stopIndex(pending) + 1);
                break;
            case R_SET:
                view.setDurationSeconds(pending);
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
