package com.morningmission.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * Grown-Ups settings, on brand at last.
 *
 * <p>This was a stock {@code AlertDialog} full of grey system buttons -- by some margin
 * the most off-brand surface in the app.
 *
 * <p>PIN entry itself is still a dialog. Re-implementing a PIN keypad on canvas is exactly
 * where a security regression comes from, and it buys nothing the dialog theme does not.
 * The hold-to-unlock panel below is an affordance that opens that dialog, not a
 * replacement for it.
 */
final class ScreenGrownUps extends Screen {

    private static final int R_BACK = 1, R_ROW = 2, R_UNLOCK = 3;

    static final int ROW_ROUTINE = 0, ROW_BUDDY = 1, ROW_SOUNDS = 2, ROW_CELEBRATION = 3,
                     ROW_TIMER = 4, ROW_KID_LOCK = 5, ROW_RESET = 6, ROW_ABOUT = 7;

    private static final String[] LABELS = {
        "Edit Routine", "Choose Buddy", "Sounds", "Celebration",
        "Timer Settings", "Kid Lock", "Reset Progress", "About"
    };
    private static final int[] GLYPHS = {
        Art.GLYPH_LIST, Art.GLYPH_HEART, Art.GLYPH_SPEAKER, Art.GLYPH_STAR,
        Art.GLYPH_REFRESH, Art.GLYPH_LOCK, Art.GLYPH_REFRESH, Art.GLYPH_PEOPLE
    };

    /** How long the unlock panel must be held. */
    private static final float HOLD_SECONDS = 3f;

    private final RectF scratch = new RectF();
    private float scroll;
    private float held;
    private boolean unlockPending;

    ScreenGrownUps(MorningView view) {
        super(view);
    }

    @Override void onEnter() {
        held = 0f;
    }

    @Override void layout(Layout layout, HitMap hits) {
        layout.scrollGrownUps(scroll);
        hits.addPadded(R_BACK, layout.guBack, layout.minTouchUnits(), 0);
        for (int i = 0; i < Layout.GROWN_UP_ROWS; i++) {
            hits.addClipped(R_ROW, layout.guRow[i], layout.guBand, i);
        }
        hits.add(R_UNLOCK, layout.guPanel);
    }

    @Override void draw(Canvas c, Layout layout, float t, float dt) {
        BuddyTheme theme = view.buddy();
        view.scene.drawBackground(c, theme, t);
        view.scene.drawForeground(c, theme, t, false);

        Paint fill = Theme.FILL;
        fill.setShader(null);
        fill.setColor(0xE8F4F8FD);
        c.drawRect(0f, 0f, Layout.W, layout.height, fill);

        Icons.glyphChip(c, Art.GLYPH_CHEVRON_LEFT, layout.guBack, 0xFFFFFFFF,
                        theme.ink, view.pressOn(R_BACK));
        Theme.textCentered(c, "Grown-Ups", layout.guTitle.centerX(),
                           layout.guTitle.centerY(), Theme.H1, Theme.INK,
                           Paint.Align.CENTER, true);

        c.save();
        c.clipRect(layout.guBand);
        for (int i = 0; i < Layout.GROWN_UP_ROWS; i++) {
            drawRow(c, layout.guRow[i], layout.guBand, theme, i);
        }
        c.restore();

        drawUnlockPanel(c, layout, theme, dt);
    }

    private void drawRow(Canvas c, RectF row, RectF clip, BuddyTheme theme, int index) {
        if (row.bottom < clip.top || row.top > clip.bottom) return;
        // One press offset for the whole row, so nothing on it moves independently.
        float sink = row.height() * 0.04f * view.pressOn(R_ROW, index);
        float middle = row.centerY() + sink;

        scratch.set(row.left, row.top + sink, row.right, row.bottom + sink);
        Theme.card(c, scratch, row.height() * 0.26f, 0xFFFFFFFF);

        float pad = row.height() * 0.18f;
        float bead = row.height() - pad * 2f;
        float bx = row.left + pad + bead * 0.5f;
        Paint fill = Theme.FILL;
        fill.setShader(null);
        fill.setColor(Theme.mix(theme.light, 0xFFFFFFFF, 0.2f));
        c.drawCircle(bx, middle, bead * 0.5f, fill);
        Icons.glyph(c, GLYPHS[index], bx, middle, bead * 0.54f, theme.ink);

        String value = valueFor(index);
        float chevron = row.height() * 0.26f;
        float valueWidth = value == null ? 0f : Theme.measure(value, Theme.B1, true) + 18f;
        scratch.set(bx + bead * 0.5f + pad, row.top + sink,
                    row.right - pad - chevron - valueWidth, row.bottom + sink);
        Theme.fitText(c, LABELS[index], scratch, Theme.T2, 16f, Theme.INK,
                      Paint.Align.LEFT, true);
        if (value != null) {
            Theme.textCentered(c, value, row.right - pad - chevron - 12f, middle,
                               Theme.B1, valueIsOn(index) ? Theme.SUCCESS_DEEP : Theme.INK_MUTED,
                               Paint.Align.RIGHT, true);
        }
        Icons.glyph(c, Art.GLYPH_CHEVRON_RIGHT, row.right - pad - chevron * 0.5f,
                    middle, chevron, Theme.INK_FAINT);
    }

    private String valueFor(int index) {
        switch (index) {
            case ROW_BUDDY: return view.buddy().name;
            case ROW_SOUNDS: return view.pref("song", true) ? "On" : "Off";
            case ROW_CELEBRATION: return view.pref("confetti", true) ? "On" : "Off";
            case ROW_TIMER: return view.minutes() + " min";
            case ROW_KID_LOCK: return view.activity.isKidLocked() ? "On" : "Off";
            default: return null;
        }
    }

    private boolean valueIsOn(int index) {
        switch (index) {
            case ROW_SOUNDS: return view.pref("song", true);
            case ROW_CELEBRATION: return view.pref("confetti", true);
            case ROW_KID_LOCK: return view.activity.isKidLocked();
            default: return false;
        }
    }

    /** Hold the panel for three seconds to reach the PIN prompt. */
    private void drawUnlockPanel(Canvas c, Layout layout, BuddyTheme theme, float dt) {
        RectF panel = layout.guPanel;
        Theme.card(c, panel, panel.height() * 0.16f, Theme.mix(theme.light, 0xFFFFFFFF, 0.35f));

        boolean holding = view.pressOn(R_UNLOCK) > 0.1f;
        held = holding ? Math.min(HOLD_SECONDS, held + dt) : Math.max(0f, held - dt * 2.4f);
        float fraction = held / HOLD_SECONDS;

        RectF bead = layout.guUnlock;
        float radius = Math.min(bead.width(), bead.height()) * 0.5f;
        Paint fill = Theme.FILL;
        Paint stroke = Theme.STROKE;
        fill.setShader(null);
        stroke.setShader(null);
        fill.setColor(0xFFFFFFFF);
        c.drawCircle(bead.centerX(), bead.centerY(), radius, fill);

        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(radius * 0.16f);
        stroke.setColor(0xFFE1EAF4);
        c.drawCircle(bead.centerX(), bead.centerY(), radius * 0.88f, stroke);
        if (fraction > 0.001f) {
            stroke.setColor(theme.primary);
            scratch.set(bead.centerX() - radius * 0.88f, bead.centerY() - radius * 0.88f,
                        bead.centerX() + radius * 0.88f, bead.centerY() + radius * 0.88f);
            c.drawArc(scratch, -90f, 360f * fraction, false, stroke);
        }
        Icons.glyph(c, Art.GLYPH_LOCK, bead.centerX(), bead.centerY(), radius * 0.86f,
                    theme.ink);

        Theme.textCentered(c, fraction > 0.02f ? "Keep holding..." : "Hold for 3 seconds to unlock",
                           panel.centerX(), panel.bottom - panel.height() * 0.16f,
                           Theme.B1, theme.ink, Paint.Align.CENTER, true);

        if (held >= HOLD_SECONDS && !unlockPending) {
            held = 0f;
            // Posted rather than called: showing a dialog from inside onDraw re-enters
            // the view hierarchy mid-frame.
            unlockPending = true;
            view.post(() -> {
                unlockPending = false;
                view.activity.requestParentUnlock();
            });
        }
    }

    @Override boolean onScroll(float dy) {
        if (view.layout.maxGrownUpsScroll() <= 0f) return false;
        scroll = view.layout.scrollGrownUps(scroll + dy);
        return true;
    }

    @Override void onRegion(int id, int data) {
        if (id == R_BACK) {
            view.route(MorningView.SCREEN_HOME);
            return;
        }
        if (id == R_UNLOCK) {
            // The panel responds to a hold, not a tap, but a tap must still say so
            // rather than appearing broken.
            view.activity.toast("Keep holding the lock for three seconds.");
            return;
        }
        if (id != R_ROW) return;
        switch (data) {
            case ROW_ROUTINE: view.route(MorningView.SCREEN_EDIT_ROUTINE); break;
            case ROW_BUDDY: view.route(MorningView.SCREEN_BUDDY_PICKER); break;
            case ROW_SOUNDS: view.setPref("song", !view.pref("song", true)); break;
            case ROW_CELEBRATION:
                boolean on = !view.pref("confetti", true);
                view.setPref("confetti", on);
                view.setPref("dance", on);
                break;
            case ROW_TIMER: view.route(MorningView.SCREEN_TIME_PICKER); break;
            case ROW_KID_LOCK: view.activity.toggleKidLock(); break;
            case ROW_RESET:
                view.resetRoutine();
                view.activity.toast("Today's progress has been cleared.");
                break;
            case ROW_ABOUT: view.activity.showAbout(); break;
            default: break;
        }
    }

    @Override boolean onBack() {
        view.route(MorningView.SCREEN_HOME);
        return true;
    }
}
