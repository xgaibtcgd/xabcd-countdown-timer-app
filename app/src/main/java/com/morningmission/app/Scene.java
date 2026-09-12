package com.morningmission.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * The illustrated world behind the buddy, drawn in code.
 *
 * <p>The eight bundled background PNGs were flat, script-generated fills -- the shark's
 * was a blue gradient with about twenty circles and triangles and a tan strip -- and were
 * the main reason the running app did not look like the approved mockups. They are gone.
 *
 * <p>Drawing the scenery instead of shipping it has three advantages beyond looking
 * better. It moves: clouds drift, kelp sways, bubbles rise, and the layers parallax. It
 * costs no download and no memory, where the PNGs were 776KB of APK and 63MB of bitmap
 * heap. And it fits any screen exactly.
 *
 * <p>That last point is the structural one. <b>Nothing here is positioned by a constant
 * Y.</b> Every layer derives from a horizon expressed as a fraction of the drawing area,
 * so a taller screen gets more sky and more ground, the way a real illustration would be
 * composed for it. The old code stretched a 9:16 bitmap into a 20:9 rectangle, which on a
 * modern phone lost a fifth of the artwork's width to cropping and upscaled the rest.
 *
 * <p>The wavy silhouettes are built once in {@link #rebuild}, called from
 * {@code onSizeChanged}; a frame is a handful of {@code drawPath} calls. Props are placed
 * by hashing their index, so a scene is scattered but identical every time it is drawn.
 */
final class Scene {

    /** The adventure: full scenery, full atmosphere. */
    static final int MODE_ADVENTURE = 0;
    /** Home: the same world, pushed back and lightened so the interface reads over it. */
    static final int MODE_HOME = 1;
    /** The celebration: a radiant burst behind the buddy. */
    static final int MODE_CELEBRATE = 2;

    // Environments, one per buddy, in buddy order.
    static final int PICNIC = 0, HIVE = 1, PARK = 2, REEF = 3,
                     JUNGLE = 4, SKY = 5, BLOSSOM = 6, VOLCANO = 7;

    /** Environment names, in buddy order, for the design preview. */
    static final String[] ENVIRONMENT_NAMES = {
        "Picnic meadow", "Flower meadow", "Park path", "Coral reef",
        "Jungle", "Above the clouds", "Blossom meadow", "Volcano valley"
    };

    // Per environment: sky top, sky middle, sky bottom, ground near, ground far,
    // horizon fraction, and the strength of the scrim behind the top interface.
    //
    // Package-visible rather than private so tools/ExportScreens.java can dump them for
    // the design preview. These are plain arrays with no graphics in their initialiser,
    // so reading them does not drag native state in off-device.
    static final int[] SKY_TOP    = {0xFFBFE9FF, 0xFFB7E6FB, 0xFFCDEBFF, 0xFF2BB3F0, 0xFFBDF0D8, 0xFF6FC4FA, 0xFFFFDCEA, 0xFFC5E9DC};
    static final int[] SKY_MID    = {0xFFDDF4FF, 0xFFD9F2F6, 0xFFE0F4FF, 0xFF1785D4, 0xFFD3F3E4, 0xFFA8DBFB, 0xFFFFEAF2, 0xFFF3EFCB};
    static final int[] SKY_LOW    = {0xFFE8F8E4, 0xFFE9F8EC, 0xFFEAF9E9, 0xFF0F63AE, 0xFFE4F6EF, 0xFFD9EFFE, 0xFFFFF4F8, 0xFFFDF0BE};
    static final int[] GROUND_NEAR= {0xFF9DDD71, 0xFFA8DE74, 0xFF9BD98E, 0xFFF3DCA4, 0xFF6FC98F, 0xFFFFFFFF, 0xFFC7ECA8, 0xFF8FC152};
    static final int[] GROUND_FAR = {0xFF7FCB5C, 0xFF85CC57, 0xFF7CC46C, 0xFFE4C88A, 0xFF4FAE72, 0xFFEAF4FF, 0xFFA8DC85, 0xFF6FA86A};
    static final float[] HORIZON  = {0.60f,      0.58f,      0.56f,      0.70f,      0.58f,      0.66f,      0.58f,      0.64f};
    static final int[] SCRIM      = {0x33,       0x33,       0x33,       0x4D,       0x33,       0x2E,       0x33,       0x33};

    /**
     * The illustrated background for this scene, or null to draw one.
     *
     * <p>These are the artwork that shipped with the project. They are 9:16, and the
     * reason they were dropped was that a modern 20:9 phone had to crop a fifth of their
     * width away. {@link #layoutBackdrop} solves that differently: the image is drawn at
     * full width and anchored to the bottom, so the ground and everything standing on it
     * is preserved exactly, and the band left above it is filled with the image's own sky
     * colour. Nothing is cropped, and the join falls in flat sky where it cannot be seen.
     */
    private Bitmap backdrop;
    private int backdropSky = 0xFFFFFFFF;
    private final RectF backdropDst = new RectF();

    private int environment = -1;
    private int mode = -1;
    /** The active buddy's light tint, kept for the celebration sky. */
    private int tint = 0xFFFFFFFF;
    private final RectF area = new RectF();
    private float horizon;

    // Built once per size change.
    private final Path farRidge = new Path();
    private final Path midRidge = new Path();
    private final Path ground = new Path();
    private final Path ray = new Path();
    private LinearGradient skyShader;
    private LinearGradient groundShader;

    // Reused every frame.
    private final RectF scratch = new RectF();
    private final Path scratchPath = new Path();

    /**
     * A stable pseudo-random value in 0..1 from an integer. Props are placed with this so
     * a scene is scattered but does not shimmer between frames or re-roll on a resize.
     */
    private static float hash(int seed) {
        int h = seed * 0x27D4EB2D;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return (h >>> 8) / (float) (1 << 24);
    }

    private static float hash(int seed, int salt) { return hash(seed * 73856093 ^ salt * 19349663); }

    /** Recomputes the scene for a new size, buddy or mode. Call from onSizeChanged. */
    void rebuild(RectF bounds, BuddyTheme theme, int newMode, Bitmap illustration) {
        area.set(bounds);
        backdrop = illustration;
        environment = theme.index;
        mode = newMode;
        tint = theme.light;

        float frac = HORIZON[environment];
        if (mode == MODE_HOME) frac = Math.min(0.74f, frac + 0.14f);
        horizon = area.top + area.height() * frac;

        cachedScrim = null;
        cachedScrimStrength = -1;
        layoutBackdrop();
        buildSky();
        buildRidges();
        buildRay();
    }

    /**
     * Places the illustration at full width against the bottom of the area, and samples
     * the colour to extend it with. Falls back to filling by height, centred, on a screen
     * squarer than the artwork.
     */
    private void layoutBackdrop() {
        if (backdrop == null || backdrop.isRecycled()) return;
        float width = area.width();
        float height = width * backdrop.getHeight() / (float) backdrop.getWidth();
        if (height >= area.height()) {
            // Wider than the art: scale to the height and take the width loss, which is
            // only reachable on a screen squarer than 9:16.
            float scaled = area.height() * backdrop.getWidth() / (float) backdrop.getHeight();
            backdropDst.set(area.centerX() - scaled * 0.5f, area.top,
                            area.centerX() + scaled * 0.5f, area.bottom);
        } else {
            backdropDst.set(area.left, area.bottom - height, area.right, area.bottom);
        }
        backdropSky = sampleTopRow(backdrop);
    }

    /** Mean colour of the artwork's top row, for extending its sky upward. */
    private static int sampleTopRow(Bitmap bitmap) {
        int width = bitmap.getWidth();
        long r = 0, g = 0, b = 0;
        int samples = 0;
        for (int x = 0; x < width; x += Math.max(1, width / 16)) {
            int pixel = bitmap.getPixel(x, 0);
            r += (pixel >> 16) & 0xFF;
            g += (pixel >> 8) & 0xFF;
            b += pixel & 0xFF;
            samples++;
        }
        if (samples == 0) return 0xFFFFFFFF;
        return 0xFF000000 | ((int) (r / samples) << 16) | ((int) (g / samples) << 8) | (int) (b / samples);
    }

    private void buildSky() {
        int top = SKY_TOP[environment], mid = SKY_MID[environment], low = SKY_LOW[environment];
        if (mode == MODE_HOME) {
            // Lift the whole sky toward white so a wordmark and cards read over it.
            top = Theme.mix(top, 0xFFFFFFFF, 0.26f);
            mid = Theme.mix(mid, 0xFFFFFFFF, 0.26f);
            low = Theme.mix(low, 0xFFFFFFFF, 0.26f);
        } else if (mode == MODE_CELEBRATE) {
            // Golden, with only a hint of the buddy's own sky. Blending the other way
            // round left the shark's celebration a washed-out mint.
            top = Theme.mix(0xFFFFD978, tint, 0.22f);
            mid = Theme.mix(0xFFFFF0BE, tint, 0.16f);
            low = Theme.mix(0xFFFFFBEC, tint, 0.12f);
        }
        skyShader = new LinearGradient(0f, area.top, 0f, horizon + area.height() * 0.12f,
                new int[]{top, mid, low}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);

        int near = GROUND_NEAR[environment];
        groundShader = new LinearGradient(0f, horizon, 0f, area.bottom,
                Theme.lighten(near, 0.06f), Theme.darken(near, 0.16f), Shader.TileMode.CLAMP);
    }

    /** The two rolling silhouettes. Amplitude scales with the drawing area, not a constant. */
    private void buildRidges() {
        float amp = area.height() * 0.045f;
        wave(farRidge, horizon - amp * 2.1f, amp * 1.3f, 3, 11);
        wave(midRidge, horizon - amp * 0.5f, amp * 1.0f, 4, 29);
        wave(ground, horizon + amp * 0.6f, amp * 0.5f, 5, 47);
    }

    /** Builds a closed wavy band from {@code baseY} down to the bottom of the area. */
    private void wave(Path out, float baseY, float amplitude, int humps, int seed) {
        out.reset();
        float w = area.width();
        out.moveTo(area.left, baseY);
        float step = w / humps;
        for (int i = 0; i < humps; i++) {
            float x0 = area.left + i * step;
            float lift = amplitude * (0.55f + hash(seed, i) * 0.9f);
            out.quadTo(x0 + step * 0.5f, baseY - lift, x0 + step, baseY + amplitude * 0.12f);
        }
        out.lineTo(area.right, area.bottom);
        out.lineTo(area.left, area.bottom);
        out.close();
    }

    /** A single light shaft, drawn repeatedly with different transforms. */
    private void buildRay() {
        ray.reset();
        float h = area.height();
        ray.moveTo(-14f, 0f);
        ray.lineTo(14f, 0f);
        ray.lineTo(72f, h * 1.1f);
        ray.lineTo(-58f, h * 1.1f);
        ray.close();
    }

    // ------------------------------------------------------------------ background

    /**
     * Sky, light, distant land and ground. Actors are drawn by the screen on top of this,
     * then {@link #drawForeground} closes over them.
     *
     * @param t seconds since the view started, for drift and sway
     */
    void drawBackground(Canvas c, BuddyTheme theme, float t) {
        if (skyShader == null) return;          // not built yet; the first measure follows
        Paint fill = Theme.FILL;
        fill.setStyle(Paint.Style.FILL);

        if (backdrop != null && !backdrop.isRecycled()) {
            drawIllustrated(c, t);
            return;
        }

        fill.setShader(skyShader);
        c.drawRect(area, fill);
        fill.setShader(null);

        if (mode == MODE_CELEBRATE) {
            drawSunburst(c, t);
        } else if (environment == REEF) {
            drawGodRays(c, t);
        } else {
            drawSun(c, t);
        }

        if (environment == SKY) {
            drawCloudBanks(c, t);
        } else {
            fill.setColor(Theme.mix(GROUND_FAR[environment], SKY_LOW[environment], 0.38f));
            c.drawPath(farRidge, fill);
            // The ground goes down before the things standing on it. Drawing the props
            // first painted the flower field, the picnic blanket, the park path and the
            // coral, and then buried all of them under the ground band -- which is why
            // five of the seven environments were reduced to the same hills and trees.
            fill.setShader(groundShader);
            c.drawPath(ground, fill);
            fill.setShader(null);
            drawGroundDetail(c, theme, t);
            drawMidProps(c, theme, t);
        }
    }

    /**
     * The illustrated route: the artwork, plus the few things code does better than a
     * still image -- shafts of light through water, and a wash that lifts the home screen
     * enough for the interface to read over it.
     */
    private void drawIllustrated(Canvas c, float t) {
        Paint fill = Theme.FILL;
        fill.setShader(null);

        if (backdropDst.top > area.top + 0.5f) {
            fill.setColor(backdropSky);
            c.drawRect(area.left, area.top, area.right, backdropDst.top + 1f, fill);
        }
        Theme.BMP.setAlpha(255);
        c.drawBitmap(backdrop, null, backdropDst, Theme.BMP);

        if (environment == REEF) drawGodRays(c, t);

        if (mode == MODE_HOME) {
            // The artwork is busy and the home screen has a wordmark, cards and a task
            // list over it. This is what the procedural sky did by lightening its stops.
            fill.setColor(0x3DFFFFFF);
            c.drawRect(area, fill);
        }
    }

    // ------------------------------------------------------------------- celestial

    private void drawSun(Canvas c, float t) {
        float r = area.width() * 0.072f;
        float cx = area.right - area.width() * 0.17f;
        float cy = area.top + area.height() * 0.10f;
        Paint fill = Theme.FILL;
        fill.setShader(null);

        fill.setColor(0x33FFE9A0);
        c.drawCircle(cx, cy, r * 2.3f, fill);
        fill.setColor(0x4DFFF0BC);
        c.drawCircle(cx, cy, r * 1.65f, fill);

        c.save();
        c.rotate((t * 5f) % 360f, cx, cy);
        fill.setColor(0x99FFCF45);
        for (int i = 0; i < 8; i++) {
            c.save();
            c.rotate(i * 45f, cx, cy);
            scratch.set(cx - r * 0.10f, cy - r * 2.05f, cx + r * 0.10f, cy - r * 1.30f);
            c.drawRoundRect(scratch, r * 0.10f, r * 0.10f, fill);
            c.restore();
        }
        c.restore();

        Clay.shapeNoShadow(c, sunDisc(), cx, cy, r * 2f, 0xFFFFD84D, Clay.GLOSSY);
    }

    private static Path sunCache;
    private static Path sunDisc() {
        if (sunCache == null) {
            // allocgate: ok - compiled once, on the first frame that needs a sun
            sunCache = Clay.compile(new float[]{Art.CIRCLE, 50f, 50f, 46f});
        }
        return sunCache;
    }

    /** Shafts of light through water, swaying slowly. */
    private void drawGodRays(Canvas c, float t) {
        Paint fill = Theme.FILL;
        fill.setShader(null);
        for (int i = 0; i < 6; i++) {
            float x = area.left + area.width() * (0.08f + 0.17f * i);
            float sway = (float) Math.sin(t * 0.42f + i * 1.7f) * 3.2f;
            float alpha = 0.16f + 0.10f * (float) Math.sin(t * 0.6f + i);
            c.save();
            c.translate(x, area.top);
            c.rotate(-6f + sway + hash(i) * 12f);
            c.scale(1f + hash(i, 5) * 0.7f, 1f);
            fill.setColor(Theme.alpha(0xFFFFFFFF, (int) (alpha * 255)));
            c.drawPath(ray, fill);
            c.restore();
        }
    }

    /** The radiant burst behind a finished mission. */
    private void drawSunburst(Canvas c, float t) {
        float cx = area.centerX();
        float cy = area.top + area.height() * 0.38f;
        float r = Math.max(area.width(), area.height());
        Paint fill = Theme.FILL;
        fill.setShader(null);
        c.save();
        c.rotate((t * 4f) % 360f, cx, cy);
        scratchPath.reset();
        for (int i = 0; i < 16; i++) {
            double a0 = Math.toRadians(i * 22.5);
            double a1 = Math.toRadians(i * 22.5 + 11.0);
            scratchPath.moveTo(cx, cy);
            scratchPath.lineTo(cx + (float) Math.cos(a0) * r, cy + (float) Math.sin(a0) * r);
            scratchPath.lineTo(cx + (float) Math.cos(a1) * r, cy + (float) Math.sin(a1) * r);
            scratchPath.close();
        }
        fill.setColor(0x1AFFFFFF);
        c.drawPath(scratchPath, fill);
        c.restore();
    }

    // ---------------------------------------------------------------- mid-ground

    private void drawMidProps(Canvas c, BuddyTheme theme, float t) {
        switch (environment) {
            case REEF:    drawKelpAndCoral(c, t); break;
            case JUNGLE:  case VOLCANO: drawJungle(c, t); break;
            case HIVE:    drawFlowerField(c, t); break;
            case BLOSSOM: drawBlossomTree(c, t); break;
            case PARK:    drawPath(c); drawTrees(c, t, 5); break;
            case PICNIC:  drawTrees(c, t, 4); drawBlanket(c); break;
            default:      drawTrees(c, t, 4); break;
        }
    }

    private void drawTrees(Canvas c, float t, int count) {
        Paint fill = Theme.FILL;
        fill.setShader(null);
        float unit = area.height() * 0.085f;
        for (int i = 0; i < count; i++) {
            float x = area.left + area.width() * (0.08f + 0.84f * hash(i, 3));
            float scale = 0.7f + hash(i, 7) * 0.6f;
            float baseY = horizon + unit * 0.2f * hash(i, 11);
            float sway = (float) Math.sin(t * 0.8f + i) * 2.4f;
            c.save();
            c.translate(x, baseY);
            c.rotate(sway);
            fill.setColor(0xFF6E4B2E);
            scratch.set(-unit * 0.10f * scale, -unit * 1.1f * scale,
                        unit * 0.10f * scale, 0f);
            c.drawRoundRect(scratch, unit * 0.08f * scale, unit * 0.08f * scale, fill);
            fill.setColor(Theme.darken(GROUND_FAR[environment], 0.14f));
            c.drawCircle(0f, -unit * 1.42f * scale, unit * 0.52f * scale, fill);
            fill.setColor(Theme.lighten(GROUND_FAR[environment], 0.10f));
            c.drawCircle(-unit * 0.22f * scale, -unit * 1.58f * scale, unit * 0.40f * scale, fill);
            c.restore();
        }
    }

    /** A path receding to the horizon: what sells travelling toward the goal. */
    private void drawPath(Canvas c) {
        Paint fill = Theme.FILL;
        fill.setShader(null);
        fill.setColor(0xFFF0DCA8);
        scratchPath.reset();
        float cx = area.centerX();
        scratchPath.moveTo(cx - area.width() * 0.05f, horizon);
        scratchPath.quadTo(cx - area.width() * 0.30f, area.bottom - area.height() * 0.10f,
                           area.left - area.width() * 0.10f, area.bottom);
        scratchPath.lineTo(area.right + area.width() * 0.10f, area.bottom);
        scratchPath.quadTo(cx + area.width() * 0.34f, area.bottom - area.height() * 0.12f,
                           cx + area.width() * 0.05f, horizon);
        scratchPath.close();
        c.drawPath(scratchPath, fill);
    }

    private void drawBlanket(Canvas c) {
        Paint fill = Theme.FILL;
        fill.setShader(null);
        float w = area.width() * 0.34f;
        float h = area.height() * 0.07f;
        float cx = area.left + area.width() * 0.22f;
        float cy = horizon + area.height() * 0.09f;
        c.save();
        c.translate(cx, cy);
        c.rotate(-7f);
        fill.setColor(0xFFE85A4C);
        scratch.set(-w / 2, -h / 2, w / 2, h / 2);
        c.drawRoundRect(scratch, h * 0.18f, h * 0.18f, fill);
        fill.setColor(0x88FFFFFF);
        for (int i = 1; i < 5; i++) {
            float x = -w / 2 + w * i / 5f;
            scratch.set(x - w * 0.012f, -h / 2, x + w * 0.012f, h / 2);
            c.drawRect(scratch, fill);
        }
        for (int i = 1; i < 3; i++) {
            float y = -h / 2 + h * i / 3f;
            scratch.set(-w / 2, y - h * 0.05f, w / 2, y + h * 0.05f);
            c.drawRect(scratch, fill);
        }
        c.restore();
    }

    private void drawKelpAndCoral(Canvas c, float t) {
        Paint fill = Theme.FILL;
        Paint stroke = Theme.STROKE;
        fill.setShader(null);
        stroke.setShader(null);
        stroke.setStyle(Paint.Style.STROKE);
        float unit = area.height() * 0.10f;

        stroke.setStrokeWidth(unit * 0.16f);
        for (int i = 0; i < 7; i++) {
            float x = area.left + area.width() * (0.05f + 0.90f * hash(i, 13));
            float h = unit * (1.1f + hash(i, 17) * 1.5f);
            float sway = (float) Math.sin(t * 0.9f + i * 0.8f) * unit * 0.22f;
            stroke.setColor(i % 2 == 0 ? 0xCC2E8F6E : 0xCC3FA87E);
            scratchPath.reset();
            scratchPath.moveTo(x, horizon + unit * 0.2f);
            scratchPath.quadTo(x + sway, horizon - h * 0.5f, x + sway * 1.7f, horizon - h);
            c.drawPath(scratchPath, stroke);
        }

        int[] coral = {0xFFFF8AA8, 0xFFB085F5, 0xFFFFB35C};
        for (int i = 0; i < 5; i++) {
            float x = area.left + area.width() * (0.08f + 0.84f * hash(i, 23));
            float s = unit * (0.5f + hash(i, 29) * 0.5f);
            fill.setColor(coral[i % coral.length]);
            for (int k = -1; k <= 1; k++) {
                c.save();
                c.translate(x + k * s * 0.34f, horizon + unit * 0.1f);
                c.rotate(k * 17f);
                scratch.set(-s * 0.13f, -s * (0.7f + Math.abs(k) * -0.2f), s * 0.13f, 0f);
                c.drawRoundRect(scratch, s * 0.13f, s * 0.13f, fill);
                c.restore();
            }
        }
    }

    /** A fern canopy across the top, which is how a tall screen gets filled. */
    private void drawJungle(Canvas c, float t) {
        Paint fill = Theme.FILL;
        fill.setShader(null);
        fill.setColor(0x8C2F8F6E);
        float leaf = area.width() * 0.32f;
        for (int i = 0; i < 4; i++) {
            float x = area.left + area.width() * (0.05f + 0.30f * i);
            float sway = (float) Math.sin(t * 0.55f + i * 1.3f) * 3f;
            c.save();
            c.translate(x, area.top - leaf * 0.22f);
            c.rotate(18f + i * 11f + sway);
            scratchPath.reset();
            scratchPath.moveTo(0f, 0f);
            scratchPath.quadTo(leaf * 0.55f, leaf * 0.18f, leaf, leaf * 0.62f);
            scratchPath.quadTo(leaf * 0.42f, leaf * 0.50f, 0f, 0f);
            scratchPath.close();
            c.drawPath(scratchPath, fill);
            c.restore();
        }
        drawTrees(c, t, 3);
    }

    private void drawFlowerField(Canvas c, float t) {
        drawTrees(c, t, 2);
        Paint fill = Theme.FILL;
        fill.setShader(null);
        int[] petals = {0xFFFF7298, 0xFFFFD75E, 0xFFB58CFF, 0xFFFFFFFF};
        float unit = area.height() * 0.022f;
        for (int i = 0; i < 14; i++) {
            float x = area.left + area.width() * hash(i, 31);
            float y = horizon + area.height() * 0.04f + area.height() * 0.22f * hash(i, 37);
            float sway = (float) Math.sin(t * 1.1f + i) * unit * 0.25f;
            fill.setColor(0xFF4FA35F);
            scratch.set(x - unit * 0.10f, y, x + unit * 0.10f, y + unit * 1.5f);
            c.drawRect(scratch, fill);
            fill.setColor(petals[i % petals.length]);
            for (int k = 0; k < 5; k++) {
                double a = k * Math.PI * 2 / 5;
                c.drawCircle(x + sway + (float) Math.cos(a) * unit * 0.5f,
                             y + (float) Math.sin(a) * unit * 0.5f, unit * 0.36f, fill);
            }
            fill.setColor(0xFFFFC24A);
            c.drawCircle(x + sway, y, unit * 0.28f, fill);
        }
    }

    private void drawBlossomTree(Canvas c, float t) {
        Paint fill = Theme.FILL;
        fill.setShader(null);
        float unit = area.height() * 0.10f;
        float x = area.left + area.width() * 0.20f;
        float sway = (float) Math.sin(t * 0.6f) * 1.6f;
        c.save();
        c.translate(x, horizon + unit * 0.1f);
        c.rotate(sway);
        fill.setColor(0xFF8A6046);
        scratch.set(-unit * 0.11f, -unit * 1.5f, unit * 0.11f, 0f);
        c.drawRoundRect(scratch, unit * 0.1f, unit * 0.1f, fill);
        int[] blossom = {0xFFFFC2D9, 0xFFFFD8E6, 0xFFFFA9C8};
        for (int i = 0; i < 7; i++) {
            fill.setColor(blossom[i % blossom.length]);
            c.drawCircle(unit * (hash(i, 41) - 0.5f) * 1.5f,
                         -unit * (1.4f + hash(i, 43) * 0.7f),
                         unit * (0.34f + hash(i, 47) * 0.24f), fill);
        }
        c.restore();
        drawTrees(c, t, 2);
    }

    /** Layered cloud banks drifting at different speeds, with wraparound. */
    private void drawCloudBanks(Canvas c, float t) {
        Paint fill = Theme.FILL;
        fill.setShader(null);
        float[] speeds = {5f, 11f, 19f};
        float[] alphas = {0.55f, 0.75f, 1f};
        float[] scales = {0.6f, 0.85f, 1.15f};
        for (int band = 0; band < 3; band++) {
            float y = area.top + area.height() * (0.16f + band * 0.17f);
            float unit = area.height() * 0.05f * scales[band];
            float span = area.width() * 0.75f;
            float shift = (t * speeds[band]) % span;
            fill.setColor(Theme.alpha(0xFFFFFFFF, (int) (alphas[band] * 255)));
            for (int i = -1; i < 3; i++) {
                float x = area.left + i * span + shift;
                puff(c, x, y + unit * hash(band, i) * 0.6f, unit, fill);
            }
        }
        // The cloud floor, so the buddy has something to stand on.
        Paint p = Theme.FILL;
        p.setShader(groundShader);
        c.drawPath(ground, p);
        p.setShader(null);
        p.setColor(0xFFFFFFFF);
        float unit = area.height() * 0.045f;
        for (int i = 0; i < 7; i++) {
            c.drawCircle(area.left + area.width() * (0.06f + 0.15f * i),
                         horizon + unit * (0.3f + hash(i, 53) * 0.4f),
                         unit * (0.7f + hash(i, 59) * 0.5f), p);
        }
        drawRainbow(c);
    }

    private void puff(Canvas c, float x, float y, float unit, Paint fill) {
        c.drawCircle(x, y, unit * 0.8f, fill);
        c.drawCircle(x + unit * 0.9f, y - unit * 0.35f, unit * 1.05f, fill);
        c.drawCircle(x + unit * 2.0f, y, unit * 0.75f, fill);
        scratch.set(x - unit * 0.8f, y - unit * 0.1f, x + unit * 2.8f, y + unit * 0.85f);
        c.drawRoundRect(scratch, unit * 0.5f, unit * 0.5f, fill);
    }

    private void drawRainbow(Canvas c) {
        Paint stroke = Theme.STROKE;
        stroke.setShader(null);
        stroke.setStyle(Paint.Style.STROKE);
        int[] bands = {0xFFFF8A8A, 0xFFFFC857, 0xFF7ED86F, 0xFF6FA8FF};
        float r = area.width() * 0.34f;
        float cx = area.right - area.width() * 0.24f;
        float cy = horizon - area.height() * 0.03f;
        stroke.setStrokeWidth(area.width() * 0.026f);
        for (int i = 0; i < bands.length; i++) {
            float rr = r - i * area.width() * 0.028f;
            stroke.setColor(Theme.alpha(bands[i], 150));
            scratch.set(cx - rr, cy - rr, cx + rr, cy + rr);
            c.drawArc(scratch, 195f, 150f, false, stroke);
        }
    }

    private void drawGroundDetail(Canvas c, BuddyTheme theme, float t) {
        Paint fill = Theme.FILL;
        fill.setShader(null);
        float unit = area.height() * 0.016f;
        int detail = environment == REEF ? 0xFFD9BF86 : Theme.darken(GROUND_NEAR[environment], 0.14f);
        for (int i = 0; i < 12; i++) {
            float x = area.left + area.width() * hash(i, 61);
            float y = horizon + area.height() * 0.05f
                    + (area.bottom - horizon) * 0.85f * hash(i, 67);
            fill.setColor(detail);
            scratch.set(x - unit * 1.3f, y - unit * 0.55f, x + unit * 1.3f, y + unit * 0.55f);
            c.drawOval(scratch, fill);
        }
    }

    // ------------------------------------------------------------------ foreground

    /**
     * Atmosphere and the readability wash, drawn after the actors.
     *
     * <p>The particles are a closed-form function of time and index rather than simulated
     * state: nothing to allocate, nothing to update, and identical on every frame for a
     * given moment.
     */
    void drawForeground(Canvas c, BuddyTheme theme, float t, boolean scrimTop) {
        if (skyShader == null) return;
        Paint fill = Theme.FILL;
        fill.setShader(null);

        int count = mode == MODE_HOME ? 10 : 20;
        float span = area.height();
        for (int i = 0; i < count; i++) {
            float x = area.left + area.width() * hash(i, 71);
            float phase = hash(i, 73);
            float speed = 0.035f + hash(i, 79) * 0.05f;
            float progress = (phase + t * speed) % 1f;
            float size = area.width() * (0.006f + hash(i, 83) * 0.010f);
            float drift = (float) Math.sin(t * 0.9f + i) * area.width() * 0.02f;

            switch (environment) {
                case REEF: {                       // bubbles rise
                    float y = area.bottom - progress * span;
                    fill.setColor(0x5CFFFFFF);
                    c.drawCircle(x + drift, y, size, fill);
                    fill.setColor(0x99FFFFFF);
                    c.drawCircle(x + drift - size * 0.3f, y - size * 0.3f, size * 0.32f, fill);
                    break;
                }
                case BLOSSOM: {                    // petals fall
                    float y = area.top + progress * span;
                    c.save();
                    c.translate(x + drift, y);
                    c.rotate((t * 60f + i * 40f) % 360f);
                    fill.setColor(i % 2 == 0 ? 0xCCFF9EC4 : 0xCCFFD3E3);
                    scratch.set(-size, -size * 0.55f, size, size * 0.55f);
                    c.drawRoundRect(scratch, size * 0.55f, size * 0.55f, fill);
                    c.restore();
                    break;
                }
                case JUNGLE: case VOLCANO: {       // leaves drift down
                    float y = area.top + progress * span;
                    c.save();
                    c.translate(x + drift, y);
                    c.rotate((t * 40f + i * 60f) % 360f);
                    fill.setColor(0xB34FAE72);
                    scratch.set(-size * 1.3f, -size * 0.5f, size * 1.3f, size * 0.5f);
                    c.drawOval(scratch, fill);
                    c.restore();
                    break;
                }
                case SKY: {                        // stars twinkle
                    float y = area.top + span * hash(i, 89) * 0.6f;
                    float twinkle = 0.4f + 0.6f * (float) Math.abs(Math.sin(t * 1.6f + i));
                    fill.setColor(Theme.alpha(0xFFFFF4C4, (int) (twinkle * 210)));
                    c.drawCircle(x, y, size * 0.8f, fill);
                    break;
                }
                default: {                         // pollen and motes drift up
                    float y = area.bottom - progress * span * 0.75f;
                    fill.setColor(Theme.alpha(0xFFFFF6C8, (int) (120 + 90 * Math.sin(t + i))));
                    c.drawCircle(x + drift, y, size * 0.7f, fill);
                    break;
                }
            }
        }

        if (scrimTop) {
            int strength = SCRIM[environment];
            if (mode == MODE_HOME) strength = Math.max(0x18, strength - 0x12);
            scratch.set(area.left, area.top, area.right, area.top + area.height() * 0.20f);
            LinearGradient top = topScrim(strength);
            fill.setShader(top);
            c.drawRect(scratch, fill);
            fill.setShader(null);
        }
    }

    private LinearGradient cachedScrim;
    private int cachedScrimStrength = -1;

    private LinearGradient topScrim(int strength) {
        if (cachedScrim == null || cachedScrimStrength != strength) {
            int dark = Theme.alpha(Theme.darken(SKY_TOP[environment], 0.55f), strength);
            // allocgate: ok - cached, rebuilt only when the scrim strength changes
            cachedScrim = new LinearGradient(0f, area.top, 0f, area.top + area.height() * 0.20f,
                    dark, dark & 0x00FFFFFF, Shader.TileMode.CLAMP);
            cachedScrimStrength = strength;
        }
        return cachedScrim;
    }

    /** Where the ground meets the sky, for placing actors on the floor of the scene. */
    float horizonY() { return horizon; }

    int environment() { return environment; }
}
