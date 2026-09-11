package com.morningmission.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * Shared visual vocabulary: colour roles, a type scale, pooled Paints, and the card,
 * text and colour helpers every screen draws through.
 *
 * <p>Three problems from the old single-file build are fixed here.
 *
 * <p><b>Typefaces are resolved once.</b> Previously {@code Typeface.create(...)} ran on
 * every single text draw -- sixty-plus allocations per frame.
 *
 * <p><b>Shadows no longer need a software layer.</b> {@code Paint.setShadowLayer} on a
 * shape is not hardware accelerated below API 28, which is why the old view called
 * {@code setLayerType(LAYER_TYPE_SOFTWARE)} and CPU-rasterised the whole screen thirty
 * times a second. {@link #card} draws two offset round rects instead: indistinguishable
 * at these radii, and fully GPU. Text shadows are kept, because {@code drawText} with a
 * shadow layer <i>is</i> accelerated.
 *
 * <p><b>Text is measured.</b> Nothing in the old build called {@code measureText}, so a
 * long task name simply ran off its card. See {@link #fitText}.
 *
 * <p>All of this is main-thread-only, like any View drawing code. The pooled Paints and
 * the {@link #timeChars} buffer are deliberately shared mutable state on that thread.
 */
final class Theme {

    private Theme() {}

    // ---------------------------------------------------------------- colour roles
    // Named by role, not by hue, so a screen never hardcodes a literal. The old build
    // had ~120 inline hex literals and a colors.xml containing a single entry.

    static final int INK          = 0xFF17345F;  // primary text on light surfaces
    static final int INK_MUTED    = 0xFF6E7FA1;  // secondary text, captions
    static final int INK_FAINT    = 0xFF9AA9BF;  // disabled, inactive nav
    static final int SURFACE      = 0xFFFFFFFF;  // cards
    static final int SURFACE_TINT = 0xFFF7FBFF;  // recessed panels
    static final int SCRIM        = 0xB00E3560;  // behind a modal sheet
    static final int CTA          = 0xFFFF7C43;  // Start Morning
    static final int CTA_DEEP     = 0xFFE2561F;  // its pressed / bottom edge
    static final int SUCCESS      = 0xFF30C764;  // I Did It, completed rows
    static final int SUCCESS_DEEP = 0xFF24864E;
    static final int LINK         = 0xFF2879ED;  // secondary actions
    static final int LINK_DEEP    = 0xFF1B5FBF;
    static final int GOLD         = 0xFFFFC857;  // stars, crowns, treasure
    static final int WARN         = 0xFFFF944E;  // the NOW chip, low time

    /** Shadow passes for {@link #card}. Two offsets read as one soft shadow. */
    private static final int SHADOW_NEAR = 0x1A0E2A4A;
    private static final int SHADOW_FAR  = 0x0F0E2A4A;

    // ------------------------------------------------------------------ type scale
    // Logical units in the 1080-wide design space, not sp: the whole UI is a scaled
    // canvas, so type has to scale with it or the layout stops matching the mockups.

    static final float D1 = 78f;  // the countdown
    static final float D2 = 56f;  // screen wordmarks
    static final float H1 = 45f;
    static final float H2 = 33f;
    static final float T1 = 30f;  // task name
    static final float T2 = 26f;
    static final float B1 = 22f;
    static final float B2 = 19f;  // task subtitle
    static final float C1 = 17f;  // nav labels, chips

    // ------------------------------------------------------------------- typefaces

    static Typeface ROUND = Typeface.DEFAULT;
    static Typeface ROUND_BOLD = Typeface.DEFAULT_BOLD;
    /** True when a bundled font was used, rather than the system "rounded" alias. */
    static boolean bundledFont;

    // ----------------------------------------------------------------------- paints
    // One Paint per purpose rather than two shared ones that every helper had to
    // defensively reset before use.

    static final Paint FILL   = new Paint(Paint.ANTI_ALIAS_FLAG);
    static final Paint STROKE = new Paint(Paint.ANTI_ALIAS_FLAG);
    static final Paint TEXT   = new Paint(Paint.ANTI_ALIAS_FLAG);
    static final Paint BMP    = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private static final Paint.FontMetrics FM = new Paint.FontMetrics();
    private static final RectF SCRATCH = new RectF();

    /**
     * Resolves typefaces once. Call from the View constructor.
     *
     * <p>"sans-serif-rounded" is a system family alias: it exists on Pixel and AOSP but
     * silently falls back to plain Roboto on many OEM builds, so the storybook look was
     * never guaranteed on a customer's device. If a font is bundled at
     * {@code res/font/mm_round.ttf} it is preferred; {@code Resources.getFont} is API 26,
     * exactly this app's minSdk.
     */
    static void init(Context context) {
        FILL.setStyle(Paint.Style.FILL);
        STROKE.setStyle(Paint.Style.STROKE);
        STROKE.setStrokeCap(Paint.Cap.ROUND);
        STROKE.setStrokeJoin(Paint.Join.ROUND);
        TEXT.setStyle(Paint.Style.FILL);

        int regular = context.getResources().getIdentifier("mm_round", "font", context.getPackageName());
        int bold = context.getResources().getIdentifier("mm_round_bold", "font", context.getPackageName());
        if (regular != 0) {
            try {
                ROUND = context.getResources().getFont(regular);
                ROUND_BOLD = bold != 0 ? context.getResources().getFont(bold)
                                       : Typeface.create(ROUND, Typeface.BOLD);
                bundledFont = true;
            } catch (Exception ignored) {
                // Fall through to the system alias below.
            }
        }
        if (!bundledFont) {
            ROUND = Typeface.create("sans-serif-rounded", Typeface.NORMAL);
            ROUND_BOLD = Typeface.create("sans-serif-rounded", Typeface.BOLD);
        }
    }

    // ------------------------------------------------------------------ colour math

    static int rgb(int color) { return color & 0x00FFFFFF; }

    static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }

    /** Scales alpha by a 0..1 factor, preserving the colour. */
    static int fade(int color, float factor) {
        int a = (int) ((color >>> 24) * clamp(factor, 0f, 1f) + 0.5f);
        return alpha(color, a);
    }

    static int mix(int a, int b, float t) {
        t = clamp(t, 0f, 1f);
        return Color.argb(
            lerp8(Color.alpha(a), Color.alpha(b), t),
            lerp8(Color.red(a),   Color.red(b),   t),
            lerp8(Color.green(a), Color.green(b), t),
            lerp8(Color.blue(a),  Color.blue(b),  t));
    }

    static int lighten(int color, float amount) { return mix(color, 0xFFFFFFFF, amount); }

    static int darken(int color, float amount) { return mix(color, 0xFF000000, amount); }

    /** Pulls a colour toward a neutral. Used for the not-yet-collected collectibles. */
    static int desaturate(int color, float amount) {
        return mix(color, 0xFFB9C6D6, amount);
    }

    private static int lerp8(int a, int b, float t) {
        int v = (int) (a + (b - a) * t + 0.5f);
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    // ------------------------------------------------------------------- surfaces

    /** A filled round rect with no shadow. */
    static void solid(Canvas c, RectF r, float radius, int color) {
        FILL.setShader(null);
        FILL.setColor(color);
        c.drawRoundRect(r, radius, radius, FILL);
    }

    /**
     * A card with a soft drop shadow, drawn as two offset passes so it stays on the GPU.
     * Replaces the old {@code shadowCard}, whose {@code setShadowLayer} forced the entire
     * view into software rendering.
     */
    static void card(Canvas c, RectF r, float radius, int color) {
        FILL.setShader(null);
        SCRATCH.set(r.left, r.top + 14f, r.right, r.bottom + 14f);
        FILL.setColor(SHADOW_FAR);
        c.drawRoundRect(SCRATCH, radius, radius, FILL);
        SCRATCH.set(r.left, r.top + 7f, r.right, r.bottom + 7f);
        FILL.setColor(SHADOW_NEAR);
        c.drawRoundRect(SCRATCH, radius, radius, FILL);
        FILL.setColor(color);
        c.drawRoundRect(r, radius, radius, FILL);
    }

    /**
     * A chunky button: a face sitting on a darker bottom edge, which is what gives the
     * mockup buttons their pressable look. {@code press} is 0 at rest and 1 held down.
     */
    static void button(Canvas c, RectF r, float radius, int face, int edge, float press) {
        float lift = lerp(10f, 2f, clamp(press, 0f, 1f));
        FILL.setShader(null);
        SCRATCH.set(r.left, r.top + lift + 8f, r.right, r.bottom + 8f);
        FILL.setColor(SHADOW_NEAR);
        c.drawRoundRect(SCRATCH, radius, radius, FILL);
        SCRATCH.set(r.left, r.top + lift, r.right, r.bottom);
        FILL.setColor(edge);
        c.drawRoundRect(SCRATCH, radius, radius, FILL);
        SCRATCH.set(r.left, r.top + lift, r.right, r.bottom - lift);
        FILL.setColor(face);
        c.drawRoundRect(SCRATCH, radius, radius, FILL);
    }

    /** A round-ended pill. */
    static void pill(Canvas c, RectF r, int color) {
        solid(c, r, r.height() * 0.5f, color);
    }

    // ----------------------------------------------------------------------- text

    /**
     * Baseline that puts the visual centre of the text at {@code cy}. Every baseline in
     * the old build was a hand-tuned constant like {@code y + 12}, which drifted as soon
     * as a size changed.
     */
    static float baselineCenter(Paint p, float cy) {
        p.getFontMetrics(FM);
        return cy - (FM.ascent + FM.descent) * 0.5f;
    }

    private static void prepare(float size, int color, Paint.Align align, boolean bold) {
        TEXT.setShader(null);
        TEXT.clearShadowLayer();
        TEXT.setTypeface(bold ? ROUND_BOLD : ROUND);
        TEXT.setTextSize(size);
        TEXT.setColor(color);
        TEXT.setTextAlign(align);
    }

    /** Draws text with {@code y} as the baseline. */
    static void text(Canvas c, String s, float x, float y,
                     float size, int color, Paint.Align align, boolean bold) {
        prepare(size, color, align, bold);
        c.drawText(s, x, y, TEXT);
    }

    /** Draws text vertically centred on {@code cy}. */
    static void textCentered(Canvas c, String s, float x, float cy,
                             float size, int color, Paint.Align align, boolean bold) {
        prepare(size, color, align, bold);
        c.drawText(s, x, baselineCenter(TEXT, cy), TEXT);
    }

    /**
     * Draws text centred in {@code box}, shrinking to fit and then ellipsising. Nothing
     * in the old build measured text, so a long custom task name overflowed its card.
     */
    static void fitText(Canvas c, String s, RectF box, float size, float minSize,
                        int color, Paint.Align align, boolean bold) {
        prepare(size, color, align, bold);
        float max = box.width();
        while (TEXT.getTextSize() > minSize && TEXT.measureText(s) > max) {
            TEXT.setTextSize(TEXT.getTextSize() - 1f);
        }
        String out = s;
        if (TEXT.measureText(out) > max) {
            float ellipsis = TEXT.measureText("...");
            int end = out.length();
            while (end > 1 && TEXT.measureText(out, 0, end) + ellipsis > max) end--;
            out = out.substring(0, end) + "...";
        }
        float x = align == Paint.Align.LEFT ? box.left
                : align == Paint.Align.RIGHT ? box.right
                : box.centerX();
        c.drawText(out, x, baselineCenter(TEXT, box.centerY()), TEXT);
    }

    /** Width of {@code s} at the given size, for laying out something beside it. */
    static float measure(String s, float size, boolean bold) {
        prepare(size, 0xFF000000, Paint.Align.LEFT, bold);
        return TEXT.measureText(s);
    }

    /**
     * Storybook title treatment: a thick white halo, then the fill with a soft drop
     * shadow. This is the one place a shadow layer is still used, because text shadows
     * are hardware accelerated.
     */
    static void wordmark(Canvas c, String s, float x, float cy, float size, int fill) {
        prepare(size, Color.WHITE, Paint.Align.CENTER, true);
        float baseline = baselineCenter(TEXT, cy);
        TEXT.setStyle(Paint.Style.STROKE);
        TEXT.setStrokeWidth(size * 0.21f);
        TEXT.setStrokeJoin(Paint.Join.ROUND);
        c.drawText(s, x, baseline, TEXT);
        TEXT.setStyle(Paint.Style.FILL);
        TEXT.setColor(fill);
        TEXT.setShadowLayer(7f, 0f, 5f, 0x30000000);
        c.drawText(s, x, baseline, TEXT);
        TEXT.clearShadowLayer();
    }

    // ------------------------------------------------------------- time formatting

    /** Draws a countdown without allocating. See {@link TimeText}. */
    static void drawTime(Canvas c, long ms, float x, float cy,
                         float size, int color, Paint.Align align) {
        int len = TimeText.format(ms);
        prepare(size, color, align, true);
        c.drawText(TimeText.BUFFER, 0, len, x, baselineCenter(TEXT, cy), TEXT);
    }
}
