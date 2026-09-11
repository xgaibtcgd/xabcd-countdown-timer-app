package com.morningmission.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/** Choosing a buddy. Two columns, as the v0.6 target shows. */
final class ScreenBuddyPicker extends Screen {

    private static final int R_CLOSE = 1, R_CARD = 2, R_CONFIRM = 3;

    private final RectF scratch = new RectF();

    ScreenBuddyPicker(MorningView view) {
        super(view);
    }

    @Override void layout(Layout layout, HitMap hits) {
        hits.addPadded(R_CLOSE, layout.pickClose, layout.minTouchUnits(), 0);
        for (int i = 0; i < BuddyTheme.COUNT; i++) {
            hits.add(R_CARD, layout.buddyCard[i], i);
        }
        hits.add(R_CONFIRM, layout.pickConfirm);
    }

    @Override void draw(Canvas c, Layout layout, float t, float dt) {
        BuddyTheme chosen = view.buddy();
        view.scene.drawBackground(c, chosen, t);
        view.scene.drawForeground(c, chosen, t, false);

        Paint fill = Theme.FILL;
        fill.setShader(null);
        fill.setColor(Theme.SCRIM);
        c.drawRect(0f, 0f, Layout.W, layout.height, fill);

        Theme.card(c, layout.pickSheet, layout.pickSheet.width() * 0.05f, 0xFFFBFDFF);

        RectF title = layout.pickTitle;
        Theme.wordmark(c, "Choose Your Buddy", title.centerX(),
                       title.top + title.height() * 0.42f,
                       Math.min(title.height() * 0.34f, Layout.W * 0.052f), 0xFF1D447E);
        Theme.textCentered(c, "Tap a buddy to hear them say hello",
                           title.centerX(), title.bottom - title.height() * 0.22f,
                           Theme.B1, Theme.INK_MUTED, Paint.Align.CENTER, false);
        Icons.glyphChip(c, Art.GLYPH_CLOSE, layout.pickClose, 0xFFEDF3FA,
                        Theme.INK_MUTED, view.pressOn(R_CLOSE));

        for (int i = 0; i < BuddyTheme.COUNT; i++) {
            drawCard(c, layout.buddyCard[i], BuddyTheme.ALL[i], i == chosen.index, i);
        }

        RectF confirm = layout.pickConfirm;
        float press = view.pressOn(R_CONFIRM);
        Theme.button(c, confirm, confirm.height() * 0.5f, chosen.primary,
                     Theme.darken(chosen.primary, 0.22f), press);
        Theme.textCentered(c, "Use " + chosen.name, confirm.centerX(),
                           confirm.centerY() + confirm.height() * 0.04f * press,
                           Theme.H2, 0xFFFFFFFF, Paint.Align.CENTER, true);
    }

    private void drawCard(Canvas c, RectF box, BuddyTheme theme, boolean selected, int index) {
        float press = view.pressOn(R_CARD, index);
        scratch.set(box);
        scratch.offset(0f, box.height() * 0.02f * press);
        Theme.card(c, scratch, scratch.height() * 0.14f,
                   Theme.mix(theme.light, 0xFFFFFFFF, selected ? 0.05f : 0.30f));

        if (selected) {
            Paint stroke = Theme.STROKE;
            stroke.setShader(null);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(scratch.height() * 0.028f);
            stroke.setColor(theme.primary);
            c.drawRoundRect(scratch, scratch.height() * 0.14f, scratch.height() * 0.14f, stroke);
        }

        float art = scratch.height() * 0.52f;
        view.drawBuddyPose(c, theme.index, scratch.centerX(),
                           scratch.top + scratch.height() * 0.66f, art,
                           Anim.DANCE, index * 0.7f);

        Theme.textCentered(c, theme.name, scratch.centerX(),
                           scratch.bottom - scratch.height() * 0.27f,
                           Math.min(Theme.T2, scratch.width() * 0.10f),
                           theme.ink, Paint.Align.CENTER, true);

        // The sound chip.
        float chipH = scratch.height() * 0.13f;
        float label = Theme.measure(theme.soundWord, Theme.B2, true);
        float chipW = chipH + 14f + label + chipH * 0.6f;
        scratch.set(scratch.centerX() - chipW * 0.5f,
                    scratch.bottom - scratch.height() * 0.115f - chipH * 0.5f,
                    scratch.centerX() + chipW * 0.5f,
                    scratch.bottom - scratch.height() * 0.115f + chipH * 0.5f);
        Theme.solid(c, scratch, chipH * 0.5f, 0xFFFFFFFF);
        Icons.glyph(c, Art.GLYPH_SPEAKER, scratch.left + chipH * 0.72f, scratch.centerY(),
                    chipH * 0.62f, theme.accent);
        Theme.textCentered(c, theme.soundWord, scratch.left + chipH * 1.2f,
                           scratch.centerY(), Theme.B2, theme.ink, Paint.Align.LEFT, true);

        if (selected) {
            float badge = box.width() * 0.10f;
            float bx = box.right - badge * 1.1f;
            float by = box.top + badge * 1.1f;
            Paint fill = Theme.FILL;
            fill.setShader(null);
            fill.setColor(theme.primary);
            c.drawCircle(bx, by, badge, fill);
            Icons.glyph(c, Art.GLYPH_CHECK, bx, by, badge * 1.2f, 0xFFFFFFFF);
        }
    }

    @Override void onRegion(int id, int data) {
        switch (id) {
            case R_CARD:
                view.setBuddy(data);
                view.activity.playBuddySound(data);
                break;
            case R_CLOSE:
            case R_CONFIRM:
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
