package com.morningmission.app;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * The material language everything drawn in code shares with the buddy artwork.
 *
 * <p>The seven buddy PNGs are soft rendered clay: rounded volumes, a single key light
 * from the upper left, a same-hue core shadow on the lower right, a bright rim on the
 * lit edge, a soft contact shadow underneath, and no outlines anywhere. Icons,
 * collectibles, goals and scenery drawn as flat vector fills sit next to that artwork and
 * look like clipart. This class applies the same passes to any shape so they read as one
 * family.
 *
 * <p>The passes, in order:
 * <ol>
 *   <li><b>Contact shadow</b> -- a soft ellipse beneath the form, as a radial gradient
 *       rather than a blur, so it stays hardware accelerated.</li>
 *   <li><b>Form gradient</b> -- filled along a fixed light axis. One global light
 *       direction is what makes forty separately drawn objects read as a single scene.</li>
 *   <li><b>Body highlight</b> -- a white radial offset toward the light. This is the pass
 *       that turns flat vector into clay, and being pure white to transparent it is
 *       colour independent, so a single cached shader serves the whole app.</li>
 *   <li><b>Rim light</b> -- a stroke of the same path, clipped to itself and offset
 *       toward the light, which leaves a crescent on the lit edge in one draw.</li>
 *   <li><b>Specular</b> -- one small highlight, on glossy forms only.</li>
 * </ol>
 *
 * <p>Geometry is authored in a 100x100 unit box as {@code float[]} command arrays (see
 * {@link Art}) and compiled to a {@link Path} once. Drawing scales the canvas rather than
 * transforming the path, so nothing allocates per frame and the rim width and gradients
 * scale with the form automatically.
 */
final class Clay {

    private Clay() {}

    /** The unit box every shape is authored in. */
    static final float UNIT = 100f;

    // Light comes from the upper left at roughly 35 degrees. Everything uses this.
    private static final float LIGHT_X = -0.55f;
    private static final float LIGHT_Y = -0.83f;

    // --------------------------------------------------------------------- flags

    /** Fill only: no gradient, no rim. For very small details. */
    static final int FLAT = 0;
    /** The form gradient and body highlight. */
    static final int MODEL = 1;
    /** A bright rim on the lit edge. */
    static final int RIM = 2;
    /** A single specular highlight. Glossy or spherical forms only. */
    static final int SPEC = 4;
    /** The full treatment for a hero form. */
    static final int SOLID = MODEL | RIM;
    /** A glossy hero form: honey, a star, treasure. */
    static final int GLOSSY = MODEL | RIM | SPEC;

    // -------------------------------------------------------------------- shaders

    /**
     * White to transparent, centred up and left in the unit box. Colour independent, so
     * one instance serves every form in the app.
     */
    private static final RadialGradient HIGHLIGHT = new RadialGradient(
            UNIT * 0.28f, UNIT * 0.24f, UNIT * 0.62f,
            new int[]{0x66FFFFFF, 0x1AFFFFFF, 0x00FFFFFF},
            new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);

    /** The core shadow that gives the lower right its weight. Also colour independent. */
    private static final RadialGradient OCCLUSION = new RadialGradient(
            UNIT * 0.74f, UNIT * 0.80f, UNIT * 0.70f,
            new int[]{0x2E000000, 0x0F000000, 0x00000000},
            new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);

    /** Soft black ellipse for contact shadows, defined in a unit circle and re-placed. */
    private static final RadialGradient CONTACT = new RadialGradient(
            0f, 0f, 1f,
            new int[]{0x3C101828, 0x1E101828, 0x00101828},
            new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);

    private static final android.graphics.Matrix CONTACT_MATRIX = new android.graphics.Matrix();
    private static final RectF SCRATCH = new RectF();

    // A small cache of form gradients, keyed by colour. There are only a few dozen
    // distinct colours across seven themes, so this fills once and never churns.
    private static final int CACHE_SIZE = 128;
    private static final int[] gradientKeys = new int[CACHE_SIZE];
    private static final LinearGradient[] gradientValues = new LinearGradient[CACHE_SIZE];

    private static LinearGradient formGradient(int color) {
        int slot = (color * 0x9E3779B1) >>> 25;          // top bits of a hash, 0..127
        for (int probe = 0; probe < 8; probe++) {
            int i = (slot + probe) & (CACHE_SIZE - 1);
            if (gradientValues[i] != null && gradientKeys[i] == color) return gradientValues[i];
            if (gradientValues[i] == null) {
                float half = UNIT * 0.5f;
                // allocgate: ok - fills the cache once per distinct colour, never per frame
                LinearGradient g = new LinearGradient(
                        half + LIGHT_X * half, half + LIGHT_Y * half,
                        half - LIGHT_X * half, half - LIGHT_Y * half,
                        Theme.lighten(color, 0.14f), Theme.darken(color, 0.17f),
                        Shader.TileMode.CLAMP);
                gradientKeys[i] = color;
                gradientValues[i] = g;
                return g;
            }
        }
        // Cache full in this probe window: fall back to an un-cached gradient. Only
        // reachable with far more distinct colours than the app defines.
        float half = UNIT * 0.5f;
        return new LinearGradient(                                  // allocgate: ok - unreachable fallback
                half + LIGHT_X * half, half + LIGHT_Y * half,
                half - LIGHT_X * half, half - LIGHT_Y * half,
                Theme.lighten(color, 0.14f), Theme.darken(color, 0.17f),
                Shader.TileMode.CLAMP);
    }

    // ------------------------------------------------------------------ transform

    /**
     * Enters the unit box: after this, draw in 0..100 coordinates and the shape lands at
     * {@code (cx, cy)} at {@code size} across. Pair with {@link #end}.
     */
    static void begin(Canvas c, float cx, float cy, float size) {
        c.save();                                   // canvasbalance: ok - paired with end()
        c.translate(cx, cy);
        float s = size / UNIT;
        c.scale(s, s);
        c.translate(-UNIT * 0.5f, -UNIT * 0.5f);
    }

    /** As {@link #begin}, with a rotation about the shape's centre. */
    static void begin(Canvas c, float cx, float cy, float size, float rotationDegrees) {
        c.save();                                   // canvasbalance: ok - paired with end()
        c.translate(cx, cy);
        c.rotate(rotationDegrees);
        float s = size / UNIT;
        c.scale(s, s);
        c.translate(-UNIT * 0.5f, -UNIT * 0.5f);
    }

    static void end(Canvas c) {
        c.restore();                                // canvasbalance: ok - paired with begin()
    }

    // ---------------------------------------------------------------------- passes

    /**
     * Draws one part of a composed form. Must be called between {@link #begin} and
     * {@link #end}.
     */
    static void part(Canvas c, Path unitPath, int color, int flags) {
        Paint fill = Theme.FILL;
        fill.setStyle(Paint.Style.FILL);

        if ((flags & MODEL) == 0) {
            fill.setShader(null);
            fill.setColor(color);
            c.drawPath(unitPath, fill);
        } else {
            fill.setShader(formGradient(color));
            fill.setColor(0xFFFFFFFF);
            c.drawPath(unitPath, fill);

            fill.setShader(OCCLUSION);
            c.drawPath(unitPath, fill);

            fill.setShader(HIGHLIGHT);
            c.drawPath(unitPath, fill);
            fill.setShader(null);
        }

        if ((flags & RIM) != 0) rim(c, unitPath);
        if ((flags & SPEC) != 0) specular(c, unitPath);
    }

    /**
     * A bright crescent on the lit edge. Clipping the path to itself and stroking an
     * offset copy leaves highlight only where the offset falls outside, which is exactly
     * the lit edge, in a single draw.
     */
    private static void rim(Canvas c, Path unitPath) {
        Paint stroke = Theme.STROKE;
        c.save();
        c.clipPath(unitPath);
        c.translate(-2.2f, -2.2f);
        stroke.setShader(null);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(4.4f);
        stroke.setColor(0x66FFFFFF);
        c.drawPath(unitPath, stroke);
        c.restore();
    }

    private static void specular(Canvas c, Path unitPath) {
        unitPath.computeBounds(SCRATCH, true);
        float w = SCRATCH.width(), h = SCRATCH.height();
        float cx = SCRATCH.left + w * 0.30f;
        float cy = SCRATCH.top + h * 0.24f;
        c.save();
        c.clipPath(unitPath);
        c.rotate(-24f, cx, cy);
        SCRATCH.set(cx - w * 0.13f, cy - h * 0.07f, cx + w * 0.13f, cy + h * 0.07f);
        Paint fill = Theme.FILL;
        fill.setShader(null);
        fill.setColor(0x8CFFFFFF);
        c.drawOval(SCRATCH, fill);
        c.restore();
    }

    /**
     * The soft shadow a form casts on whatever it sits on. Drawn in the destination
     * coordinate space, before {@link #begin}.
     */
    static void contactShadow(Canvas c, float cx, float cy, float radiusX, float radiusY,
                              float strength) {
        Paint fill = Theme.FILL;
        CONTACT_MATRIX.reset();
        CONTACT_MATRIX.setScale(radiusX, radiusY);
        CONTACT_MATRIX.postTranslate(cx, cy);
        CONTACT.setLocalMatrix(CONTACT_MATRIX);
        fill.setShader(CONTACT);
        fill.setAlpha((int) (255 * Theme.clamp(strength, 0f, 1f)));
        SCRATCH.set(cx - radiusX, cy - radiusY, cx + radiusX, cy + radiusY);
        c.drawOval(SCRATCH, fill);
        fill.setShader(null);
        fill.setAlpha(255);
    }

    /** Contact shadow sized for a form of {@code size} sitting at {@code (cx, cy)}. */
    static void groundShadow(Canvas c, float cx, float cy, float size) {
        contactShadow(c, cx, cy + size * 0.44f, size * 0.42f, size * 0.13f, 1f);
    }

    // ------------------------------------------------------------- whole-form draws

    /** Draws a single-part form with a contact shadow. */
    static void shape(Canvas c, Path unitPath, float cx, float cy, float size,
                      int color, int flags) {
        groundShadow(c, cx, cy, size);
        begin(c, cx, cy, size);
        part(c, unitPath, color, flags);
        end(c);
    }

    /** Draws a single-part form with no contact shadow, for things already in a scene. */
    static void shapeNoShadow(Canvas c, Path unitPath, float cx, float cy, float size,
                              int color, int flags) {
        begin(c, cx, cy, size);
        part(c, unitPath, color, flags);
        end(c);
    }

    // ---------------------------------------------------------------- path compiler

    /**
     * Compiles a command array from {@link Art} into a Path. Called once per shape at
     * class initialisation, never per frame.
     */
    static Path compile(float[] commands) {
        Path path = new Path();
        int i = 0;
        while (i < commands.length) {
            int op = (int) commands[i++];
            switch (op) {
                case Art.MOVE:
                    path.moveTo(commands[i++], commands[i++]);
                    break;
                case Art.LINE:
                    path.lineTo(commands[i++], commands[i++]);
                    break;
                case Art.QUAD:
                    path.quadTo(commands[i++], commands[i++], commands[i++], commands[i++]);
                    break;
                case Art.CUBIC:
                    path.cubicTo(commands[i++], commands[i++], commands[i++],
                                 commands[i++], commands[i++], commands[i++]);
                    break;
                case Art.CLOSE:
                    path.close();
                    break;
                case Art.OVAL: {
                    float l = commands[i++], t = commands[i++];
                    float r = commands[i++], b = commands[i++];
                    path.addOval(l, t, r, b, Path.Direction.CW);
                    break;
                }
                case Art.CIRCLE: {
                    float cx = commands[i++], cy = commands[i++], rad = commands[i++];
                    path.addCircle(cx, cy, rad, Path.Direction.CW);
                    break;
                }
                case Art.HOLE: {
                    // Wound the other way, so the default non-zero fill rule cuts it out.
                    float cx = commands[i++], cy = commands[i++], rad = commands[i++];
                    path.addCircle(cx, cy, rad, Path.Direction.CCW);
                    break;
                }
                case Art.RRECT: {
                    float l = commands[i++], t = commands[i++];
                    float r = commands[i++], b = commands[i++];
                    float rx = commands[i++], ry = commands[i++];
                    path.addRoundRect(l, t, r, b, rx, ry, Path.Direction.CW);
                    break;
                }
                default:
                    throw new IllegalArgumentException(
                            "Art command array has an unknown opcode " + op + " at " + (i - 1));
            }
        }
        return path;
    }

    /** Compiles a set of command arrays in one go. */
    static Path[] compileAll(float[][] shapes) {
        Path[] out = new Path[shapes.length];        // allocgate: ok - class initialisation
        for (int i = 0; i < shapes.length; i++) {
            // allocgate: ok - class initialisation
            out[i] = shapes[i] == null || shapes[i].length == 0 ? new Path() : compile(shapes[i]);
        }
        return out;
    }
}
