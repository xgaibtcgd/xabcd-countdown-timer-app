package com.morningmission.app;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * A pooled particle system, used for the celebration confetti and for one-off bursts.
 *
 * <p>What it replaces was not a particle system at all: forty-six axis-aligned rectangles
 * whose position was a closed-form function of the loop index and the frame counter,
 * wrapped with a modulo. Pieces teleported when they hit an edge, drifted diagonally
 * instead of falling, never rotated or faded, and cycled through five colours in strict
 * order, which banded visibly.
 *
 * <p>Everything is parallel arrays sized once at construction, so a burst of two hundred
 * pieces allocates nothing.
 */
final class Particles {

    static final int SHAPE_RECT = 0;
    static final int SHAPE_RIBBON = 1;
    static final int SHAPE_DOT = 2;
    /** An {@link Art} glyph -- a heart, a star, a Z -- rather than a scrap of paper. */
    static final int SHAPE_GLYPH = 3;

    /** Package-visible so tools/SelfTest.java can hold a celebration's volley to it. */
    static final int CAPACITY = 220;
    private static final float GRAVITY = 900f;
    private static final float DRAG = 1.6f;

    private final float[] x = new float[CAPACITY];
    private final float[] y = new float[CAPACITY];
    private final float[] vx = new float[CAPACITY];
    private final float[] vy = new float[CAPACITY];
    private final float[] rotation = new float[CAPACITY];
    private final float[] spin = new float[CAPACITY];
    private final float[] size = new float[CAPACITY];
    private final float[] aspect = new float[CAPACITY];
    private final float[] life = new float[CAPACITY];
    private final float[] maxLife = new float[CAPACITY];
    private final int[] color = new int[CAPACITY];
    private final int[] shape = new int[CAPACITY];
    /** Which {@link Art} glyph, for {@link #SHAPE_GLYPH}. */
    private final int[] glyph = new int[CAPACITY];
    /**
     * Per-piece gravity multiplier.
     *
     * <p>Confetti falls; an emote rises and fades. One number per piece rather than two
     * update loops, and it costs one more array sized once at construction like the
     * eleven beside it.
     */
    private final float[] pull = new float[CAPACITY];

    private int count;
    private int nextSeed = 1;
    private final RectF scratch = new RectF();

    boolean isActive() { return count > 0; }

    void clear() { count = 0; }

    private float random() {
        nextSeed = nextSeed * 1103515245 + 12345;
        return ((nextSeed >>> 8) & 0xFFFFFF) / (float) 0xFFFFFF;
    }

    private float random(float lo, float hi) { return lo + (hi - lo) * random(); }

    /**
     * Fires a spray of pieces.
     *
     * @param angleDeg centre of the spread, in degrees; -90 is straight up
     * @param spreadDeg total width of the spread
     */
    void burst(int pieces, float originX, float originY, float angleDeg, float spreadDeg,
               float speedLow, float speedHigh, int[] palette) {
        for (int i = 0; i < pieces && count < CAPACITY; i++) {
            int slot = count++;
            double angle = Math.toRadians(angleDeg + random(-spreadDeg * 0.5f, spreadDeg * 0.5f));
            float speed = random(speedLow, speedHigh);
            x[slot] = originX + random(-14f, 14f);
            y[slot] = originY + random(-14f, 14f);
            vx[slot] = (float) Math.cos(angle) * speed;
            vy[slot] = (float) Math.sin(angle) * speed;
            rotation[slot] = random(0f, 360f);
            spin[slot] = random(-540f, 540f);
            size[slot] = random(11f, 26f);
            aspect[slot] = random(0.35f, 1f);
            maxLife[slot] = random(2.4f, 4.2f);
            life[slot] = maxLife[slot];
            color[slot] = palette[(int) (random() * palette.length) % palette.length];
            float roll = random();
            shape[slot] = roll < 0.55f ? SHAPE_RECT : (roll < 0.85f ? SHAPE_RIBBON : SHAPE_DOT);
            glyph[slot] = 0;
            pull[slot] = 1f;
        }
    }

    /**
     * A few glyphs drifting up off the buddy: hearts for a poke, Zs for sleeping, sweat
     * for hurrying, a puff of dust under a landing.
     *
     * <p>Rides the same pool and the same update loop as the confetti, so it allocates
     * nothing and fades out the same way. What it does not share is gravity -- these go
     * up and keep going.
     *
     * @param glyphId an {@link Art} GLYPH_* constant
     * @param pieces  two or three; a dozen hearts reads as a mistake, not as affection
     * @param rise    how fast they leave, in design units per second
     * @param spread  horizontal scatter, in design units
     */
    void emote(int glyphId, int pieces, float originX, float originY, float glyphSize,
               int colour, float rise, float spread, float gravityScale) {
        for (int i = 0; i < pieces && count < CAPACITY; i++) {
            int slot = count++;
            x[slot] = originX + random(-spread, spread);
            y[slot] = originY + random(-spread * 0.4f, spread * 0.4f);
            vx[slot] = random(-spread * 1.4f, spread * 1.4f);
            vy[slot] = -rise * random(0.75f, 1.25f);
            rotation[slot] = random(-14f, 14f);
            spin[slot] = random(-70f, 70f);
            size[slot] = glyphSize * random(0.8f, 1.2f);
            aspect[slot] = 1f;
            maxLife[slot] = random(0.85f, 1.35f);
            life[slot] = maxLife[slot];
            color[slot] = colour;
            shape[slot] = SHAPE_GLYPH;
            glyph[slot] = glyphId;
            pull[slot] = gravityScale;
        }
    }

    /**
     * The celebration: two corner cannons plus a drizzle from the top, which is what the
     * mockup shows. The palette is seeded with the buddy's own colours, so the confetti
     * belongs to the character rather than always being the same five hues.
     */
    void celebrate(RectF area, BuddyTheme theme, int[] scratchPalette) {
        scratchPalette[0] = theme.primary;
        scratchPalette[1] = theme.accent;
        scratchPalette[2] = Theme.GOLD;
        scratchPalette[3] = 0xFFFF6B6B;
        scratchPalette[4] = 0xFF5ED6F2;
        scratchPalette[5] = 0xFF9B7BFF;
        burst(64, area.left + area.width() * 0.06f, area.bottom - area.height() * 0.10f,
              -68f, 44f, 1500f, 2300f, scratchPalette);
        burst(64, area.right - area.width() * 0.06f, area.bottom - area.height() * 0.10f,
              -112f, 44f, 1500f, 2300f, scratchPalette);
        burst(40, area.centerX(), area.top - 40f, 90f, 120f, 120f, 420f, scratchPalette);
    }

    /** Advances the simulation. Dead pieces are swapped in from the end of the pool. */
    void update(float dt) {
        for (int i = 0; i < count; ) {
            life[i] -= dt;
            if (life[i] <= 0f) {
                int last = --count;
                if (i != last) copy(last, i);
                continue;
            }
            vy[i] += GRAVITY * pull[i] * dt;
            float damping = 1f - DRAG * dt;
            if (damping < 0f) damping = 0f;
            vx[i] *= damping;
            vy[i] *= damping;
            x[i] += vx[i] * dt;
            y[i] += vy[i] * dt;
            rotation[i] += spin[i] * dt;
            i++;
        }
    }

    private void copy(int from, int to) {
        x[to] = x[from];           y[to] = y[from];
        vx[to] = vx[from];         vy[to] = vy[from];
        rotation[to] = rotation[from]; spin[to] = spin[from];
        size[to] = size[from];     aspect[to] = aspect[from];
        life[to] = life[from];     maxLife[to] = maxLife[from];
        color[to] = color[from];   shape[to] = shape[from];
        glyph[to] = glyph[from];   pull[to] = pull[from];
    }

    void draw(Canvas c) {
        if (count == 0) return;
        Paint fill = Theme.FILL;
        fill.setShader(null);
        fill.setStyle(Paint.Style.FILL);
        for (int i = 0; i < count; i++) {
            // Fade over the last quarter of a piece's life, so nothing pops out. The
            // fade SCALES the colour's own alpha rather than replacing it: Theme.alpha
            // overwrites the channel, so a piece emitted deliberately translucent -- the
            // sleeping Zs -- would otherwise snap to solid for three quarters of its
            // life and only look soft on the way out.
            float remaining = life[i] / maxLife[i];
            float fade = remaining >= 0.25f ? 1f : remaining / 0.25f;
            int alpha = (int) (((color[i] >>> 24) & 0xFF) * fade);
            fill.setColor(Theme.alpha(color[i], alpha));

            float half = size[i] * 0.5f;
            if (shape[i] == SHAPE_GLYPH) {
                Icons.glyph(c, glyph[i], x[i], y[i], size[i],
                            Theme.alpha(color[i], alpha), rotation[i]);
                continue;
            }
            if (shape[i] == SHAPE_DOT) {
                c.drawCircle(x[i], y[i], half * 0.7f, fill);
                continue;
            }
            c.save();
            c.translate(x[i], y[i]);
            c.rotate(rotation[i]);
            float halfHeight = half * (shape[i] == SHAPE_RIBBON ? aspect[i] * 0.45f : aspect[i]);
            scratch.set(-half, -halfHeight, half, halfHeight);
            c.drawRoundRect(scratch, halfHeight * 0.5f, halfHeight * 0.5f, fill);
            c.restore();
        }
    }
}
