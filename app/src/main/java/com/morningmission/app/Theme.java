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
    /**
     * The display face: headings, button labels, the wordmark and the countdown.
     *
     * <p>Nunito is a text face. At wordmark size it is too light for artwork this heavy,
     * and the mockup's lettering is visibly a different, chunkier family. Baloo 2
     * ExtraBold is that family. Body copy stays in Nunito, which is the more readable of
     * the two at task-row size.
     */
    static Typeface DISPLAY = Typeface.DEFAULT_BOLD;
    /** True when a bundled font was used, rather than the system "rounded" alias. */
    static boolean bundledFont;

    // ----------------------------------------------------------------------- paints
    // One Paint per purpose rather than two shared ones that every helper had to
    // defensively reset before use.

    static final Paint FILL   = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Always {@code Style.STROKE}. Set the width and colour; never change the style. */
    static final Paint STROKE = new Paint(Paint.ANTI_ALIAS_FLAG);
    static final Paint TEXT   = new Paint(Paint.ANTI_ALIAS_FLAG);
    static final Paint BMP    = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private static final Paint.FontMetrics FM = new Paint.FontMetrics();
    private static final RectF SCRATCH = new RectF();
    private static final android.graphics.Matrix SHADER_MATRIX = new android.graphics.Matrix();

    /**
     * White fading out downward, for the highlight along the top of a curved surface.
     * Colour-independent, so one instance serves every button, chip and bubble.
     */
    private static final android.graphics.LinearGradient GLOSS =
            new android.graphics.LinearGradient(0f, 0f, 0f, 1f,
                    new int[]{0x59FFFFFF, 0x1FFFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.55f, 1f}, android.graphics.Shader.TileMode.CLAMP);

    // A button face gradient per colour, defined in 0..1 and placed with a matrix, so
    // nothing is allocated per frame. Same approach as Clay.formGradient.
    private static final int FACE_CACHE = 32;
    private static final int[] faceKeys = new int[FACE_CACHE];
    private static final android.graphics.LinearGradient[] faceValues =
            new android.graphics.LinearGradient[FACE_CACHE];

    private static android.graphics.LinearGradient faceGradient(int color) {
        int slot = (color * 0x9E3779B1) >>> 27;          // 0..31
        for (int probe = 0; probe < 6; probe++) {
            int i = (slot + probe) & (FACE_CACHE - 1);
            if (faceValues[i] != null && faceKeys[i] == color) return faceValues[i];
            if (faceValues[i] == null) {
                // allocgate: ok - fills the cache once per distinct colour
                faceValues[i] = new android.graphics.LinearGradient(0f, 0f, 0f, 1f,
                        lighten(color, 0.18f), darken(color, 0.06f),
                        android.graphics.Shader.TileMode.CLAMP);
                faceKeys[i] = color;
                return faceValues[i];
            }
        }
        return null;                                      // fall back to a flat fill
    }

    /** Maps a 0..1 vertical shader onto {@code top..bottom}. */
    private static void placeShader(android.graphics.Shader shader, float top, float height) {
        SHADER_MATRIX.reset();
        SHADER_MATRIX.setScale(1f, Math.max(1f, height));
        SHADER_MATRIX.postTranslate(0f, top);
        shader.setLocalMatrix(SHADER_MATRIX);
    }

    /**
     * Resolves typefaces once. Call from the View constructor.
     *
     * <p>The app bundles Nunito (Regular and ExtraBold) as {@code res/font/mm_round}.
     * The old build asked for "sans-serif-rounded", which is a system family alias: it
     * exists on Pixel and AOSP but silently falls back to plain Roboto on many
     * manufacturers' builds, so the rounded storybook lettering was never actually
     * guaranteed on a customer's device. {@code Resources.getFont} is API 26, exactly
     * this app's minSdk. The alias remains as a fallback if the resource cannot be
     * loaded for any reason.
     *
     * <p>Nunito is licensed under the SIL Open Font License; see licenses/nunito-OFL.txt.
     */
    static void init(Context context) {
        FILL.setStyle(Paint.Style.FILL);
        STROKE.setStyle(Paint.Style.STROKE);
        STROKE.setStrokeCap(Paint.Cap.ROUND);
        STROKE.setStrokeJoin(Paint.Join.ROUND);
        TEXT.setStyle(Paint.Style.FILL);

        try {
            ROUND = context.getResources().getFont(R.font.mm_round);
            ROUND_BOLD = context.getResources().getFont(R.font.mm_round_bold);
            bundledFont = ROUND != null && ROUND_BOLD != null;
        } catch (Exception ignored) {
            // Fall through to the system alias below.
        }
        if (!bundledFont) {
            ROUND = Typeface.create("sans-serif-rounded", Typeface.NORMAL);
            ROUND_BOLD = Typeface.create("sans-serif-rounded", Typeface.BOLD);
        }
        try {
            DISPLAY = context.getResources().getFont(R.font.mm_display);
        } catch (Exception ignored) {
            DISPLAY = null;
        }
        // Losing the display face costs character, not legibility: fall back to the text
        // face's bold rather than leaving headings unset.
        if (DISPLAY == null) DISPLAY = ROUND_BOLD;
        tabularSize = -1f;   // measured lazily against whichever face we ended up with
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
     * A candy button: a soft halo, a darker bottom edge, a gradient face and a gloss
     * along the top. The edge is what makes it read as pressable; the gloss is what makes
     * it read as a sweet rather than a Material surface.
     *
     * @param press 0 at rest, 1 held down -- the lift compresses and the shine dulls
     * @param glow strength of the outer halo, for a primary action; 0 for the rest
     */
    static void button(Canvas c, RectF r, float radius, int face, int edge,
                       float press, float glow) {
        float held = clamp(press, 0f, 1f);
        float lift = lerp(10f, 2f, held);
        FILL.setShader(null);

        if (glow > 0.01f) {
            float spread = lerp(16f, 8f, held) * clamp(glow, 0f, 1f);
            FILL.setColor(alpha(face, (int) (40 * clamp(glow, 0f, 1f) * (1f - held * 0.4f))));
            SCRATCH.set(r.left - spread, r.top + lift - spread * 0.4f,
                        r.right + spread, r.bottom + spread * 0.8f);
            c.drawRoundRect(SCRATCH, radius + spread, radius + spread, FILL);
        }

        SCRATCH.set(r.left, r.top + lift + 8f, r.right, r.bottom + 8f);
        FILL.setColor(SHADOW_NEAR);
        c.drawRoundRect(SCRATCH, radius, radius, FILL);

        SCRATCH.set(r.left, r.top + lift, r.right, r.bottom);
        FILL.setColor(edge);
        c.drawRoundRect(SCRATCH, radius, radius, FILL);

        SCRATCH.set(r.left, r.top + lift, r.right, r.bottom - lift);
        android.graphics.LinearGradient gradient = faceGradient(face);
        if (gradient != null) {
            placeShader(gradient, SCRATCH.top, SCRATCH.height());
            FILL.setShader(gradient);
            FILL.setColor(0xFFFFFFFF);
        } else {
            FILL.setColor(face);
        }
        c.drawRoundRect(SCRATCH, radius, radius, FILL);
        FILL.setShader(null);

        gloss(c, SCRATCH, radius, 1f - held * 0.55f);
    }

    /** Convenience for the many buttons that carry no halo. */
    static void button(Canvas c, RectF r, float radius, int face, int edge, float press) {
        button(c, r, radius, face, edge, press, 0f);
    }

    /**
     * The highlight along the top of a curved surface. Shared by buttons, the round chips
     * and the minute bubbles, so every tappable thing in the app is made of one material.
     *
     * @param strength 0 to 1
     */
    static void gloss(Canvas c, RectF r, float radius, float strength) {
        float amount = clamp(strength, 0f, 1f);
        if (amount <= 0.01f) return;
        float inset = Math.min(r.width(), r.height()) * 0.06f;
        SCRATCH.set(r.left + inset, r.top + inset * 0.6f,
                    r.right - inset, r.top + r.height() * 0.48f);
        if (SCRATCH.height() <= 1f) return;
        placeShader(GLOSS, SCRATCH.top, SCRATCH.height());
        FILL.setShader(GLOSS);
        FILL.setAlpha((int) (255 * amount));
        float capRadius = Math.max(2f, radius - inset);
        c.drawRoundRect(SCRATCH, capRadius, capRadius, FILL);
        FILL.setShader(null);
        FILL.setAlpha(255);
    }

    /** A gloss sized for a circle of {@code radius} centred on a point. */
    static void glossCircle(Canvas c, float cx, float cy, float radius, float strength) {
        SCRATCH.set(cx - radius, cy - radius, cx + radius, cy + radius);
        gloss(c, SCRATCH, radius, strength);
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
        prepare(size, color, align, bold ? ROUND_BOLD : ROUND);
    }

    private static void prepare(float size, int color, Paint.Align align, Typeface face) {
        TEXT.setShader(null);
        TEXT.clearShadowLayer();
        // wordmark() strokes a halo pass; reset defensively so a later plain draw is
        // never accidentally outlined.
        TEXT.setStyle(Paint.Style.FILL);
        TEXT.setTypeface(face);
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

    // --------------------------------------------------------------------- labels
    // Button labels and screen titles are set in DISPLAY rather than the text face.
    // They are short, they sit on coloured surfaces, and they are where the chunky
    // storybook lettering of the artwork belongs. Body copy keeps using text()/fitText().

    /** Width of a display-face label, for laying out a glyph beside it. */
    static float measureLabel(String s, float size) {
        prepare(size, 0xFF000000, Paint.Align.LEFT, DISPLAY);
        return TEXT.measureText(s);
    }

    /**
     * The largest size at or below {@code size} (and never under {@code minSize}) at which
     * {@code s} fits {@code max} in the display face.
     *
     * <p>Baloo 2 is wider than Nunito, so a label that fitted before might not now. A call
     * site that places a glyph beside its text has to know the final size before it can
     * measure anything, hence resolving it separately rather than inside the draw.
     */
    static float labelSize(String s, float size, float minSize, float max) {
        prepare(size, 0xFF000000, Paint.Align.LEFT, DISPLAY);
        while (TEXT.getTextSize() > minSize && TEXT.measureText(s) > max) {
            TEXT.setTextSize(TEXT.getTextSize() - 1f);
        }
        return TEXT.getTextSize();
    }

    /**
     * The type size a label should take to fill {@code box} as a button.
     *
     * <p>Button labels used to be set at the fixed {@link #H2}, which on a 175-unit
     * button is 19% of its height -- a large empty shape with small text in it, and
     * visibly smaller than the glyph drawn beside it. Derive it from the button instead,
     * then let {@link #labelSize} shrink it further if the string is long.
     */
    static float buttonLabelSize(RectF box) {
        return clamp(box.height() * 0.36f, 22f, 68f);
    }

    /**
     * The type size for the primary line of a list row or card.
     *
     * <p>Same defect as the button labels, on the surfaces that were not buttons: a task
     * name was set at the fixed {@link #T1}, which is 18% of a 168-unit row and sat
     * beside a 121-unit icon. Rows happen to be a fixed height today
     * ({@link Layout#TASK_ROW_H}; the bands around them are what scroll), so this is
     * really a ratio fix -- but deriving it from the rect means the cards whose height
     * genuinely does vary, like the adventure task card, get the right size for free.
     */
    static float rowTitleSize(RectF row) {
        return clamp(row.height() * 0.25f, 20f, 48f);
    }

    /** The secondary line under {@link #rowTitleSize}: a subtitle, or a row's value. */
    static float rowSubtitleSize(RectF row) {
        return clamp(row.height() * 0.165f, 14f, 32f);
    }

    /**
     * Horizontal room a label has inside a pill button: the full width less a pill's
     * worth of end padding, and less {@code taken} for anything sharing the line.
     */
    static float labelRoom(RectF box, float taken) {
        return box.width() - box.height() * 0.5f - taken;
    }

    /** A display-face label, vertically centred on {@code cy}. */
    static void label(Canvas c, String s, float x, float cy,
                      float size, int color, Paint.Align align) {
        prepare(size, color, align, DISPLAY);
        c.drawText(s, x, baselineCenter(TEXT, cy), TEXT);
    }

    /** A display-face label centred in {@code box}, shrinking and ellipsising to fit. */
    static void labelFit(Canvas c, String s, RectF box, float size, float minSize,
                         int color, Paint.Align align) {
        prepare(size, color, align, DISPLAY);
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

    /**
     * Storybook title treatment: a thick white halo, then the fill with a soft drop
     * shadow. This is the one place a shadow layer is still used, because text shadows
     * are hardware accelerated.
     */
    static void wordmark(Canvas c, String s, float x, float cy, float size, int fill) {
        prepare(size, Color.WHITE, Paint.Align.CENTER, DISPLAY);
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

    /**
     * The wordmark with each letter in its own colour, as the Morning Mission logo does.
     * Letters are placed by walking their advances, so the spacing is the font's own.
     */
    static void wordmarkLetters(Canvas c, String s, float cx, float cy,
                                float size, int[] colors) {
        prepare(size, Color.WHITE, Paint.Align.LEFT, DISPLAY);
        float total = TEXT.measureText(s);
        float baseline = baselineCenter(TEXT, cy);
        float x = cx - total * 0.5f;

        TEXT.setStyle(Paint.Style.STROKE);
        TEXT.setStrokeWidth(size * 0.21f);
        TEXT.setStrokeJoin(Paint.Join.ROUND);
        c.drawText(s, x, baseline, TEXT);

        TEXT.setStyle(Paint.Style.FILL);
        TEXT.setShadowLayer(7f, 0f, 5f, 0x30000000);
        float cursor = x;
        for (int i = 0; i < s.length(); i++) {
            TEXT.setColor(colors[i % colors.length]);
            c.drawText(s, i, i + 1, cursor, baseline, TEXT);
            cursor += TEXT.measureText(s, i, i + 1);
        }
        TEXT.clearShadowLayer();
    }

    // ------------------------------------------------------------- time formatting

    /**
     * Widest digit in the display face at {@link #tabularSize}. Baloo 2 has no tabular
     * figures, so a 1 is far narrower than a 4 and an unaligned countdown twitches
     * sideways every second -- on the app's hero element. Measured once per size.
     */
    private static float tabularSize = -1f;
    private static float tabularAdvance;
    private static final char[] DIGIT = new char[1];

    /** Assumes TEXT is already prepared at {@code size} in the display face. */
    private static float digitAdvance(float size) {
        if (size == tabularSize) return tabularAdvance;
        float widest = 0f;
        for (char d = '0'; d <= '9'; d++) {
            DIGIT[0] = d;
            float w = TEXT.measureText(DIGIT, 0, 1);
            if (w > widest) widest = w;
        }
        tabularSize = size;
        tabularAdvance = widest;
        return widest;
    }

    /**
     * Draws a countdown without allocating. See {@link TimeText}. Every digit is centred
     * in a cell one widest-digit wide, so the clock never shifts as it ticks; the colon
     * keeps its own narrow advance.
     */
    static void drawTime(Canvas c, long ms, float x, float cy,
                         float size, int color, Paint.Align align) {
        int len = TimeText.format(ms);
        prepare(size, color, Paint.Align.LEFT, DISPLAY);
        float step = digitAdvance(size);

        float total = 0f;
        for (int i = 0; i < len; i++) {
            total += TimeText.BUFFER[i] == ':' ? TEXT.measureText(TimeText.BUFFER, i, 1) : step;
        }
        float cursor = align == Paint.Align.CENTER ? x - total * 0.5f
                     : align == Paint.Align.RIGHT  ? x - total
                     : x;
        float baseline = baselineCenter(TEXT, cy);
        for (int i = 0; i < len; i++) {
            float w = TEXT.measureText(TimeText.BUFFER, i, 1);
            if (TimeText.BUFFER[i] == ':') {
                c.drawText(TimeText.BUFFER, i, 1, cursor, baseline, TEXT);
                cursor += w;
            } else {
                c.drawText(TimeText.BUFFER, i, 1, cursor + (step - w) * 0.5f, baseline, TEXT);
                cursor += step;
            }
        }
    }

    /** Width the countdown will occupy, for centring something under it. */
    static float measureTime(long ms, float size) {
        int len = TimeText.format(ms);
        prepare(size, 0xFF000000, Paint.Align.LEFT, DISPLAY);
        float step = digitAdvance(size);
        float total = 0f;
        for (int i = 0; i < len; i++) {
            total += TimeText.BUFFER[i] == ':' ? TEXT.measureText(TimeText.BUFFER, i, 1) : step;
        }
        return total;
    }
}
