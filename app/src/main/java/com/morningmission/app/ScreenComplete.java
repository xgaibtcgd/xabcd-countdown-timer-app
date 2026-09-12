package com.morningmission.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
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
    /** A second scratch: the chase needs one rect while writing points into another. */
    private final RectF scratch2 = new RectF();
    private int praiseIndex;

    /** Which celebration this morning drew. Set on entry, held for the visit. */
    private int mode;

    /**
     * Fireworks in flight: where each shell is, how fast it is rising, and how long
     * since it burst.
     *
     * <p>Shells rather than particles. A particle in the pool has no fuse and no
     * "explode into more of me when you get there", and giving it one would mean new
     * physics in a system every screen shares for the sake of one mode. A shell is four
     * floats the screen integrates itself, and it calls the pool's ordinary burst when
     * it reaches the top.
     */
    private final float[] shellX = new float[Celebration.SHELLS];
    private final float[] shellY = new float[Celebration.SHELLS];
    private final float[] shellVy = new float[Celebration.SHELLS];
    private final float[] shellFuse = new float[Celebration.SHELLS];
    /** Seconds since each shell burst, or negative while it is still climbing. */
    private final float[] shellAge = new float[Celebration.SHELLS];
    private final int[] palette = new int[6];

    /** Seconds until the next wave of confetti, for the modes that keep topping up. */
    private float refill;

    /**
     * Seconds since the screen arrived.
     *
     * <p>Its own clock rather than the {@code t} every draw is handed, which counts from
     * when the view attached. The lights have to go down on ARRIVAL; driven from app
     * time they would already be down before the screen had appeared, on any morning
     * that took longer than a second.
     */
    private float since;

    ScreenComplete(MorningView view) {
        super(view);
    }

    @Override void onEnter() {
        praiseIndex = (int) ((System.currentTimeMillis() / 1000L) % PRAISE.length);
        // From the morning's own seed, so it is the same celebration the opening volley
        // on the countdown screen already started, and a different one tomorrow.
        mode = Celebration.modeFor(view.engine.routeSeed());
        refill = 0.9f;
        since = 0f;
        for (int i = 0; i < Celebration.SHELLS; i++) {
            shellFuse[i] = 0.35f + i * 0.75f;
            shellAge[i] = -1f;
            shellY[i] = 0f;
        }
    }

    @Override void layout(Layout layout, HitMap hits) {
        hits.add(R_PLAY_AGAIN, layout.cmpPlayAgain);
        hits.add(R_HOME, layout.cmpBackHome);
    }

    @Override void draw(Canvas c, Layout layout, float t, float dt) {
        BuddyTheme theme = view.buddy();
        Engine engine = view.engine;

        view.scene.drawBackground(c, theme, t);
        // The party lighting sits on the sky and under everything that stands in it.
        // Gated on the same preference as the confetti: one switch turns the lot off.
        boolean party = view.pref("confetti", true);
        if (party) {
            since += dt;
            advanceCelebration(layout, theme, dt);
            drawDusk(c, layout);
            drawLights(c, layout, theme, t);
        }

        RectF title = layout.cmpTitle;
        float size = Math.min(title.height() * 0.44f, Layout.W * 0.075f);
        Theme.wordmark(c, "Mission", title.centerX(), title.top + title.height() * 0.32f,
                       size, 0xFF2879ED);
        Theme.wordmark(c, "Complete!", title.centerX(), title.top + title.height() * 0.80f,
                       size * 1.08f, 0xFFEC4777);

        drawStage(c, layout, theme, t);
        if (party) drawSparks(c, layout, theme, t);
        view.scene.drawForeground(c, theme, t, false);
        drawCard(c, layout, theme, engine);
        drawButtons(c, layout, theme);
        view.particles.draw(c);
    }

    // ------------------------------------------------------------------ the party

    /**
     * Moves the celebration on by {@code dt}: shells climb, waves top up.
     *
     * <p>The only mutable thing on this screen. Everything else it draws is a pure
     * function of {@code t}, which is what {@link Celebration} is for; a firework has to
     * remember where it got to.
     */
    private void advanceCelebration(Layout layout, BuddyTheme theme, float dt) {
        RectF play = layout.play;
        paletteFor(theme);

        if (mode == Celebration.MODE_FIREWORKS) {
            for (int i = 0; i < Celebration.SHELLS; i++) {
                if (shellAge[i] >= 0f) {
                    shellAge[i] += dt;
                    // Spent, and long enough ago that the flash has gone: send it up again.
                    if (shellAge[i] > Celebration.FLASH_LIFE + 1.6f) {
                        shellAge[i] = -1f;
                        shellFuse[i] = 0.2f + i * 0.55f;
                    }
                    continue;
                }
                if (shellFuse[i] > 0f) {
                    shellFuse[i] -= dt;
                    if (shellFuse[i] > 0f) continue;
                    // Launch. Across the width rather than from one spot, so three in
                    // the air do not read as one repeating firework.
                    shellX[i] = play.left + play.width() * (0.22f + 0.28f * i);
                    shellY[i] = play.bottom;
                    shellVy[i] = -play.height() * (0.62f + 0.06f * i);
                }
                shellY[i] += shellVy[i] * dt;
                shellVy[i] += play.height() * 0.34f * dt;      // gravity, in screen units
                if (shellVy[i] >= -play.height() * 0.06f) {
                    shellAge[i] = 0f;                          // apex: burst
                    view.particles.burst(Celebration.SHELL_PIECES, shellX[i], shellY[i],
                                         -90f, 360f, 260f, 620f, palette);
                }
            }
            return;
        }

        // The rest keep topping themselves up, in small waves. One 168-piece volley is
        // spent well before the card lands, and a pool that has run dry looks like the
        // celebration finished early.
        refill -= dt;
        if (refill > 0f) return;
        refill = 1.5f;
        switch (mode) {
            case Celebration.MODE_CONFETTI:
                view.particles.burst(26, play.left + play.width() * 0.08f,
                                     play.bottom - play.height() * 0.10f,
                                     -66f, 40f, 1400f, 2100f, palette);
                view.particles.burst(26, play.right - play.width() * 0.08f,
                                     play.bottom - play.height() * 0.10f,
                                     -114f, 40f, 1400f, 2100f, palette);
                break;
            case Celebration.MODE_DISCO:
            case Celebration.MODE_CHASE:
                view.particles.burst(18, play.centerX(), play.top - 30f,
                                     90f, 130f, 110f, 380f, palette);
                break;
            case Celebration.MODE_STARFALL:
                // Big slow stars FALLING, spread across the width, with barely any
                // gravity so they drift down rather than arc. A negative rise makes
                // emote's upward throw a downward one.
                refill = 0.85f;
                view.particles.emote(Art.GLYPH_STAR, 5,
                                     play.left + play.width() * (0.16f + 0.68f * drift()),
                                     play.top - play.height() * 0.02f,
                                     play.width() * 0.075f, Theme.GOLD,
                                     -90f, play.width() * 0.16f, 0.16f);
                break;
            default:
                break;
        }
    }

    /** A wandering 0..1, so the starfall does not always drop down the same line. */
    private float drift() {
        driftSeed = driftSeed * 1103515245 + 12345;
        return ((driftSeed >>> 8) & 0xFFFF) / 65535f;
    }

    private int driftSeed = 7;

    /** The buddy's colours plus the fixed party four, the way Particles.celebrate does. */
    private void paletteFor(BuddyTheme theme) {
        palette[0] = theme.primary;
        palette[1] = theme.accent;
        palette[2] = Theme.GOLD;
        palette[3] = 0xFFFF6B6B;
        palette[4] = 0xFF5ED6F2;
        palette[5] = 0xFF9B7BFF;
    }

    /**
     * The lights going down, for the modes that need darkness to be seen at all.
     *
     * <p>A flat scrim over the sky. The first render of this change had four disco cones
     * and three firework flashes drawn in white on a near-white gold sky, which is to
     * say it had none of them: the five modes came out indistinguishable. The dim is
     * what makes a light a light.
     */
    private void drawDusk(Canvas c, Layout layout) {
        float d = Celebration.dusk(mode, since);
        if (d <= 0f) return;
        Paint fill = Theme.FILL;
        fill.setShader(null);
        fill.setColor(Theme.alpha(Celebration.DUSK_COLOUR, (int) (255 * d)));
        c.drawRect(layout.play, fill);
        fill.setAlpha(255);
    }

    /**
     * The lighting: cones, washes and bulbs, on the sky and behind everything else.
     *
     * <p>All of it built out of what the app already draws with. There is no blend mode
     * anywhere in this codebase -- no Xfermode, no saveLayer, no additive pass -- and
     * this is not the screen to introduce one on. {@link Clay#contactShadow} is a cached
     * radial gradient re-placed by matrix, which is exactly a soft glow at an arbitrary
     * size and alpha, and it is what every light pool and firework flash here is.
     */
    private void drawLights(Canvas c, Layout layout, BuddyTheme theme, float t) {
        RectF play = layout.play;
        float glow = Celebration.glow(mode, t);
        switch (mode) {
            case Celebration.MODE_DISCO: {
                // Four cones sweeping from a point above the stage. Same shape as
                // Scene.drawGodRays: one form, drawn a few times under a rotation.
                float cx = play.centerX(), cy = play.top + play.height() * 0.06f;
                for (int i = 0; i < 4; i++) {
                    float deg = Celebration.coneAngle(i, 4, t);
                    int tint = discoTint(theme, i, t);
                    c.save();
                    c.translate(cx, cy);
                    c.rotate(deg);
                    // The beam. Two passes: a wide soft one and a tighter bright core,
                    // which is what stops a single radial reading as a smudge.
                    Theme.FILL.setColorFilter(null);
                    coneGlow(c, tint, 0f, play.height() * 0.36f,
                             play.width() * 0.17f, play.height() * 0.36f, 0.34f * glow);
                    coneGlow(c, tint, 0f, play.height() * 0.30f,
                             play.width() * 0.075f, play.height() * 0.30f, 0.40f * glow);
                    c.restore();
                    // A pool of the same colour where the beam lands.
                    float px = cx + (float) Math.sin(Math.toRadians(deg)) * play.width() * 0.30f;
                    Theme.FILL.setShader(null);
                    Theme.FILL.setColor(Theme.alpha(tint, (int) (110 * glow)));
                    c.drawCircle(px, play.bottom - play.height() * 0.16f,
                                 play.width() * 0.15f, Theme.FILL);
                }
                break;
            }
            case Celebration.MODE_CHASE: {
                // A border of bulbs round the stage, one lit brighter as it passes.
                // Inset from the stage, so the string frames the buddy instead of
                // hugging the screen edge where half of it falls behind the card.
                float inset = play.width() * 0.05f;
                scratch2.set(layout.cmpStage.left + inset, layout.cmpStage.top + inset,
                             layout.cmpStage.right - inset, layout.cmpStage.bottom - inset);
                float phase = Celebration.chasePhase(t);
                for (int i = 0; i < CHASE_BULBS; i++) {
                    float f = i / (float) CHASE_BULBS;
                    float along = f - phase;
                    along -= (float) Math.floor(along);
                    float lit = along < 0.18f ? 1f - along / 0.18f : 0.22f;
                    borderPoint(scratch2, f, scratch);
                    int bulb = i % 2 == 0 ? Theme.GOLD : theme.accent;
                    // A halo under each lit bulb, or a 10-unit dot on a dimmed scene is
                    // a speck. The bulb is the dot; the halo is what makes it a light.
                    if (lit > 0.30f) {
                        coneGlow(c, bulb, scratch.left, scratch.top,
                                 play.width() * 0.045f, play.width() * 0.045f,
                                 0.55f * lit * glow);
                    }
                    Theme.FILL.setShader(null);
                    Theme.FILL.setColor(Theme.alpha(bulb, (int) (255 * (0.35f + 0.65f * lit) * glow)));
                    c.drawCircle(scratch.left, scratch.top,
                                 play.width() * (0.0090f + 0.0075f * lit), Theme.FILL);
                }
                break;
            }
            default:
                break;
        }
        Theme.FILL.setShader(null);
        Theme.FILL.setAlpha(255);
    }

    /**
     * A coloured soft blob.
     *
     * <p>{@link Clay#contactShadow} is the app's cached radial gradient re-placed by
     * matrix, which is exactly the right shape, but it is a fixed dark colour -- it is a
     * shadow. Tinting it needs a colour filter, and the filter has to come off again or
     * every later draw on this frame wears it.
     */
    private static void coneGlow(Canvas c, int tint, float cx, float cy,
                                 float rx, float ry, float strength) {
        Theme.FILL.setColorFilter(tintFilter(tint));
        Clay.contactShadow(c, cx, cy, rx, ry, strength);
        Theme.FILL.setColorFilter(null);
    }

    /** One cached filter per colour, so a per-frame tint does not allocate. */
    private static PorterDuffColorFilter tintFilter(int colour) {
        if (colour != filterColour || filter == null) {
            filterColour = colour;
            filter = makeFilter(colour);
        }
        return filter;
    }

    private static PorterDuffColorFilter makeFilter(int colour) {
        return new PorterDuffColorFilter(colour, PorterDuff.Mode.SRC_IN);
    }

    private static PorterDuffColorFilter filter;
    private static int filterColour;

    /** How many bulbs run round the stage in chase mode. */
    private static final int CHASE_BULBS = 44;

    /**
     * A point {@code f} of the way round the rectangle, anticlockwise from top-left.
     *
     * <p>Written into {@code out.left/top} rather than returned, because returning a
     * point means allocating one and this runs forty-four times a frame.
     */
    private static void borderPoint(RectF box, float f, RectF out) {
        float w = box.width(), h = box.height(), per = 2f * (w + h);
        float d = f * per;
        if (d < w)            { out.left = box.left + d;         out.top = box.top; }
        else if (d < w + h)   { out.left = box.right;            out.top = box.top + (d - w); }
        else if (d < 2f * w + h) { out.left = box.right - (d - w - h); out.top = box.bottom; }
        else                  { out.left = box.left;             out.top = box.bottom - (d - 2f * w - h); }
    }

    /** Cone {@code i}'s colour, cycling round the wheel without changing brightness. */
    private static int discoTint(BuddyTheme theme, int i, float t) {
        float hue = Celebration.hueCycle(t) + i * 0.25f;
        hue -= (float) Math.floor(hue);
        int[] wheel = DISCO_WHEEL;
        float scaled = hue * wheel.length;
        int a = (int) scaled;
        int b = (a + 1) % wheel.length;
        return Theme.mix(wheel[a % wheel.length], wheel[b], scaled - a);
    }

    /** Party colours at roughly even brightness, so the cycle reads as hue, not flicker. */
    private static final int[] DISCO_WHEEL = {
        0xFFFF6B6B, 0xFFFFC857, 0xFF7BE38B, 0xFF5ED6F2, 0xFF9B7BFF, 0xFFFF8ED4,
    };

    /** The bits that go OVER the buddy: firework flashes and the starfall shimmer. */
    private void drawSparks(Canvas c, Layout layout, BuddyTheme theme, float t) {
        if (mode != Celebration.MODE_FIREWORKS) return;
        RectF play = layout.play;
        for (int i = 0; i < Celebration.SHELLS; i++) {
            if (shellAge[i] < 0f) {
                // Still climbing: a bright head with a short tail behind it.
                if (shellY[i] <= 0f || shellY[i] >= play.bottom) continue;
                Theme.FILL.setShader(null);
                Theme.FILL.setColor(Theme.alpha(Theme.GOLD, 210));
                c.drawCircle(shellX[i], shellY[i], play.width() * 0.010f, Theme.FILL);
                Theme.FILL.setColor(Theme.alpha(Theme.GOLD, 70));
                c.drawCircle(shellX[i], shellY[i] + play.height() * 0.02f,
                             play.width() * 0.006f, Theme.FILL);
                continue;
            }
            float flash = Celebration.burstFlash(shellAge[i]);
            if (flash <= 0f) continue;
            Clay.contactShadow(c, shellX[i], shellY[i],
                               play.width() * 0.26f, play.width() * 0.26f, 0.42f * flash);
        }
        Theme.FILL.setShader(null);
        Theme.FILL.setAlpha(255);
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
        // The chest wide open and EMPTY, with the star drawn separately above it rather
        // than the frame that has one painted in. The painted one has no face and cannot
        // move; the separate one is the same star that climbs out of the chest at the end
        // of the morning, and it has a face and can dance. The plain star moulded onto
        // the outside of the chest is part of every frame and stays as it is -- that one
        // is a decoration on a box, not a character.
        view.drawProp(c, MorningView.PROP_CHEST_OPEN, gx, gy, goalSize, 0f, 255);
        float sway = (float) Math.sin(t * 2.4f);
        float hop = (float) Math.sin(t * 1.6f);
        view.drawProp(c, MorningView.PROP_STAR,
                      gx + sway * goalSize * 0.07f,
                      gy - goalSize * (0.34f + 0.045f * hop),
                      goalSize * (0.50f + 0.03f * hop), sway * 11f, 255);

        float height = Math.min(stage.height() * 0.80f, Layout.W * 0.50f);
        float cx = stage.left + stage.width() * 0.40f;
        float feet = stage.bottom - stage.height() * 0.06f;
        view.drawBuddyCheering(c, theme.index, cx, feet, height, true);

        // The crown rides on top of the buddy, following the same bob. Anchored to where
        // the artwork actually starts, not to the top of its frame -- the frames are
        // padded, by different amounts per pose, and the crown was floating clear of
        // some heads by a tenth of a body.
        float bob = (float) Math.sin(t * 6.4f) * 20f;
        float crest = feet - height * (1f - view.cheerTopFraction(theme.index));
        Icons.glyph(c, Art.GLYPH_CROWN, cx, crest + bob - height * 0.06f,
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
            // Two ways to get here with no time left on the clock: finishing on the
            // buzzer, and the buzzer finishing for you. Saying "you finished your
            // mission" to a child who ran out with two tasks left is the app telling
            // them something they can see is not true.
            boolean finished = engine.allDone();
            String headline = finished ? "You finished your mission!" : "Time is up!";
            // Sized to the block rather than to a constant. It was H2 or a quarter over
            // the caption, whichever was bigger, which on a tall card left the one line
            // the card is about looking like a footnote. fitText fills the width it is
            // given and steps down only if the string is too long for it.
            float top = finished ? 0.16f : 0.10f;
            scratch.set(box.left + box.width() * 0.06f, box.top + box.height() * top,
                        box.right - box.width() * 0.06f,
                        box.top + box.height() * (finished ? 0.66f : 0.44f));
            Theme.fitText(c, headline, scratch, Theme.D2, 22f,
                          Theme.INK, Paint.Align.CENTER, true);
            if (!finished) {
                Theme.textCentered(c, engine.completedCount() + " of " + engine.taskCount()
                                   + " done. That still counts!", box.centerX(),
                                   box.top + box.height() * 0.60f, caption,
                                   Theme.INK_MUTED, Paint.Align.CENTER, false);
            }
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
