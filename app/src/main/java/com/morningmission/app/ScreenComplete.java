package com.morningmission.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * The celebration.
 *
 * <p>New in this version. The old build drew a small card on top of the adventure screen
 * and fired the victory music as a side effect of {@code onDraw}.
 *
 * <p>The frozen time is the point of the screen: a child who finished with 3:42 left sees
 * 3:42, and it does not tick down behind the dance.
 */
final class ScreenComplete extends Screen {

    private static final int R_PLAY_AGAIN = 1, R_HOME = 2;

    /** Rotated so the same child does not read the same line every morning. */
    private static final String[] PRAISE = {
        "Amazing! You're a Morning Hero!",
        "Brilliant! That was your best yet.",
        "Superstar! Everything done and dusted.",
        "Wonderful! You're ready for anything.",
        "Fantastic! What a way to start the day.",
        "Champion! Your buddy is so proud.",
    };

    private final RectF scratch = new RectF();
    private int praiseIndex;

    ScreenComplete(MorningView view) {
        super(view);
    }

    @Override void onEnter() {
        praiseIndex = (int) ((System.currentTimeMillis() / 1000L) % PRAISE.length);
    }

    @Override void layout(Layout layout, HitMap hits) {
        hits.add(R_PLAY_AGAIN, layout.cmpPlayAgain);
        hits.add(R_HOME, layout.cmpBackHome);
    }

    @Override void draw(Canvas c, Layout layout, float t, float dt) {
        BuddyTheme theme = view.buddy();
        Engine engine = view.engine;

        view.scene.drawBackground(c, theme, t);

        RectF title = layout.cmpTitle;
        float size = Math.min(title.height() * 0.44f, Layout.W * 0.075f);
        Theme.wordmark(c, "Mission", title.centerX(), title.top + title.height() * 0.32f,
                       size, 0xFF2879ED);
        Theme.wordmark(c, "Complete!", title.centerX(), title.top + title.height() * 0.80f,
                       size * 1.08f, 0xFFEC4777);

        drawStage(c, layout, theme, t);
        view.scene.drawForeground(c, theme, t, false);
        drawCard(c, layout, theme, engine);
        drawButtons(c, layout, theme);
        view.particles.draw(c);
    }

    private void drawStage(Canvas c, Layout layout, BuddyTheme theme, float t) {
        RectF stage = layout.cmpStage;
        float goalSize = Math.min(stage.height() * 0.42f, Layout.W * 0.30f);
        float gx = stage.right - goalSize * 0.62f;
        float gy = stage.bottom - goalSize * 0.55f;
        // The open chest, pulsing, where each character used to have its own goal.
        Paint glow = Theme.FILL;
        glow.setShader(null);
        float pulse = 0.75f + 0.25f * (float) Math.sin(t * 2f);
        glow.setColor(Theme.alpha(Theme.GOLD, (int) (70 * pulse)));
        c.drawCircle(gx, gy, goalSize * 0.72f, glow);
        glow.setColor(Theme.alpha(Theme.GOLD, (int) (48 * pulse)));
        c.drawCircle(gx, gy, goalSize * 0.56f, glow);
        Clay.contactShadow(c, gx, gy + goalSize * 0.42f, goalSize * 0.40f, goalSize * 0.12f, 1f);
        view.drawProp(c, MorningView.PROP_CHEST_FULL, gx, gy, goalSize, 0f, 255);

        float height = Math.min(stage.height() * 0.80f, Layout.W * 0.50f);
        float cx = stage.left + stage.width() * 0.40f;
        float feet = stage.bottom - stage.height() * 0.06f;
        view.drawBuddy(c, theme.index, cx, feet, height, true);

        // The crown rides on top of the buddy, following the same bob.
        float bob = (float) Math.sin(t * 6.4f) * 20f;
        Icons.glyph(c, Art.GLYPH_CROWN, cx, feet - height + bob - height * 0.06f,
                    height * 0.26f, Theme.GOLD);

        for (int i = 0; i < 7; i++) {
            float angle = t * 0.8f + i * 0.9f;
            float radius = height * (0.52f + 0.10f * (float) Math.sin(t * 1.3f + i));
            float sx = cx + (float) Math.cos(angle) * radius;
            float sy = feet - height * 0.55f + (float) Math.sin(angle) * radius * 0.55f;
            float twinkle = 0.4f + 0.6f * Math.abs((float) Math.sin(t * 2.2f + i));
            Icons.glyph(c, Art.GLYPH_STAR, sx, sy, height * 0.075f * twinkle,
                        Theme.alpha(Theme.GOLD, (int) (twinkle * 220)));
        }
    }

    private void drawCard(Canvas c, Layout layout, BuddyTheme theme, Engine engine) {
        RectF box = layout.cmpCard;
        Theme.card(c, box, box.height() * 0.20f, 0xFAFFFFFF);
        long remaining = engine.completionRemainingMs();

        float caption = Theme.cardCaptionSize(box);
        if (remaining > 0L) {
            Theme.textCentered(c, "You finished with", box.centerX(),
                               box.top + box.height() * 0.19f, caption,
                               Theme.INK_MUTED, Paint.Align.CENTER, false);
            Theme.drawTime(c, remaining, box.centerX(), box.top + box.height() * 0.48f,
                           Math.min(Theme.D1, box.height() * 0.36f), Theme.INK,
                           Paint.Align.CENTER);
            Theme.textCentered(c, "left on the clock!", box.centerX(),
                               box.top + box.height() * 0.68f, caption,
                               Theme.INK_MUTED, Paint.Align.CENTER, false);
        } else {
            Theme.textCentered(c, "You finished your mission!", box.centerX(),
                               box.top + box.height() * 0.38f,
                               Math.max(Theme.H2, caption * 1.25f),
                               Theme.INK, Paint.Align.CENTER, true);
        }

        scratch.set(box.left + box.width() * 0.05f, box.bottom - box.height() * 0.26f,
                    box.right - box.width() * 0.05f, box.bottom - box.height() * 0.03f);
        Theme.fitText(c, PRAISE[praiseIndex], scratch, caption * 1.05f, 18f,
                      theme.ink, Paint.Align.CENTER, true);
    }

    private void drawButtons(Canvas c, Layout layout, BuddyTheme theme) {
        RectF play = layout.cmpPlayAgain;
        float press = view.pressOn(R_PLAY_AGAIN);
        Theme.button(c, play, play.height() * 0.5f, Theme.SUCCESS,
                     Theme.SUCCESS_DEEP, press, 1f);
        labelled(c, play, press, Art.GLYPH_REFRESH, "Play Again", 0xFFFFFFFF);

        RectF home = layout.cmpBackHome;
        float homePress = view.pressOn(R_HOME);
        Theme.button(c, home, home.height() * 0.5f, 0xFFFFFFFF, 0xFFDCE6F2, homePress);
        labelled(c, home, homePress, Art.GLYPH_HOME, "Back to Home", theme.ink);
    }

    private void labelled(Canvas c, RectF box, float press, int glyph, String label, int colour) {
        float cy = box.centerY() + box.height() * 0.04f * press;
        float size = box.height() * 0.38f;
        float room = Theme.labelRoom(box, size + 18f);
        float textSize = Theme.labelSize(label, Theme.buttonLabelSize(box), 16f, room);
        float textWidth = Theme.measureLabel(label, textSize);
        float startX = box.centerX() - (size + 18f + textWidth) * 0.5f;
        Icons.glyph(c, glyph, startX + size * 0.5f, cy, size, colour);
        Theme.label(c, label, startX + size + 18f, cy, textSize, colour,
                    Paint.Align.LEFT);
    }

    @Override int tapSound(int id, int data) {
        return id == R_PLAY_AGAIN ? Sounds.UI_CONFIRM : Sounds.UI_TAP;
    }

    @Override void onRegion(int id, int data) {
        // Neither button leaves kid mode: getting out still needs the grown-ups PIN.
        if (id == R_PLAY_AGAIN) {
            view.resetRoutine();
            view.route(MorningView.SCREEN_HOME);
        } else if (id == R_HOME) {
            view.resetRoutine();
            view.route(MorningView.SCREEN_HOME);
        }
    }

    @Override boolean onBack() {
        view.activity.requestParentUnlock();
        return true;
    }
}
