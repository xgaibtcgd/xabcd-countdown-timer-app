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

    private static final int CAPACITY = 220;
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
            vy[i] += GRAVITY * dt;
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
    }

    void draw(Canvas c) {
        if (count == 0) return;
        Paint fill = Theme.FILL;
        fill.setShader(null);
        fill.setStyle(Paint.Style.FILL);
        for (int i = 0; i < count; i++) {
            // Fade over the last quarter of a piece's life, so nothing pops out.
            float remaining = life[i] / maxLife[i];
            int alpha = remaining >= 0.25f ? 255 : (int) (255 * remaining / 0.25f);
            fill.setColor(Theme.alpha(color[i], alpha));

            float half = size[i] * 0.5f;
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
